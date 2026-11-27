package com.socialhub.grouppublishingtask.dispatch;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * Durable consumer reservation and outcome, separate DB transactions from external I/O.
 */
final class GroupProductionExecutionStore {
    enum Decision {START, DUPLICATE, REJECT}

    record Reservation(Decision decision, UUID token, GroupProductionPublisher.Request request) {
    }

    private record Candidate(UUID postId, String externalGroupId, String taskState, String outboxState,
                             boolean eligible) {
    }

    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    GroupProductionExecutionStore(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    Reservation reserve(GroupDispatchEnvelope event) {
        return Objects.requireNonNull(tx.execute(status -> {
            var candidates = db.query("""
                            SELECT t.post_id,g.external_group_id,t.status,o.state,
                                   COALESCE(p.approval_status='APPROVED' AND p.status IN ('READY','SCHEDULED','PUBLISHED'),false)
                            FROM group_dispatch_outbox o JOIN group_publishing_tasks t ON t.id=o.task_id
                            JOIN facebook_groups g ON g.id=t.facebook_group_id
                            JOIN posts p ON p.id=t.post_id AND p.workspace_id=g.workspace_id
                            WHERE o.event_id=? AND t.id=? AND g.workspace_id=? AND g.id=?
                            FOR UPDATE OF t,o
                            """, (rs, n) -> new Candidate(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)),
                    event.eventId(), event.taskId(), event.workspaceId(), event.groupId());
            if (candidates.size() != 1) return new Reservation(Decision.REJECT, null, null);
            Boolean exists = db.queryForObject("SELECT EXISTS(SELECT 1 FROM group_dispatch_inbox WHERE event_id=? AND task_id=?)", Boolean.class, event.eventId(), event.taskId());
            if (Boolean.TRUE.equals(exists)) return new Reservation(Decision.DUPLICATE, null, null);
            var candidate = candidates.getFirst();
            if (!"PROCESSING".equals(candidate.taskState()) || "DEAD".equals(candidate.outboxState()))
                return new Reservation(Decision.REJECT, null, null);
            UUID token = UUID.randomUUID();
            db.update("""
                    INSERT INTO group_dispatch_inbox(event_id,task_id,state,execution_token,review_after)
                    VALUES (?,?,'STARTED',?,now()+interval '15 minutes')
                    """, event.eventId(), event.taskId(), token);
            if (!candidate.eligible()) {
                finishLocked(event, token, "NEEDS_REVIEW", null, "APPROVAL_CHANGED");
                return new Reservation(Decision.DUPLICATE, null, null);
            }
            return new Reservation(Decision.START, token, new GroupProductionPublisher.Request(event, candidate.postId(), candidate.externalGroupId()));
        }));
    }

    boolean finish(GroupDispatchEnvelope event, UUID token, String state, String link, String code) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var locked = db.query("SELECT id FROM group_publishing_tasks WHERE id=? FOR UPDATE", (rs, n) -> rs.getObject(1, UUID.class), event.taskId());
            if (locked.size() != 1) return false;
            return finishLocked(event, token, state, link, code);
        }));
    }

    private boolean finishLocked(GroupDispatchEnvelope event, UUID token, String state, String link, String code) {
        if (!java.util.Set.of("PUBLISHED", "FAILED", "NEEDS_REVIEW").contains(state))
            throw new IllegalArgumentException("Invalid terminal state");
        int changed = db.update("""
                UPDATE group_dispatch_inbox SET state=?,completed_at=now(),result_code=?
                WHERE event_id=? AND task_id=? AND execution_token=? AND state='STARTED'
                """, state, code, event.eventId(), event.taskId(), token);
        if (changed == 0) return false;
        int updated = db.update("""
                UPDATE group_publishing_tasks SET status=?,published_url=?,
                    published_at=CASE WHEN ?='PUBLISHED' THEN now() ELSE NULL END
                WHERE id=? AND status='PROCESSING'
                """, state, link, state, event.taskId());
        if (updated != 1) throw new IllegalStateException("Task ownership changed; roll back outcome");
        return true;
    }

    // Crash/timeout: never acquire execution rights again. Route to human reconciliation.
    // Lock ordering matches reserve/finish: task before inbox.
    int reviewExpired(UUID workspaceId, UUID groupId, int limit) {
        if (workspaceId == null || groupId == null || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid review scope");
        return Objects.requireNonNull(tx.execute(status -> {
            var events = db.query("""
                    SELECT i.event_id,i.task_id,i.execution_token FROM group_dispatch_inbox i
                    JOIN group_publishing_tasks t ON t.id=i.task_id
                    JOIN facebook_groups g ON g.id=t.facebook_group_id
                    JOIN posts p ON p.id=t.post_id AND p.workspace_id=g.workspace_id
                    WHERE i.state='STARTED' AND i.review_after<=now() AND t.status='PROCESSING'
                    AND g.workspace_id=? AND g.id=? ORDER BY i.review_after,i.event_id
                    LIMIT ? FOR UPDATE OF t SKIP LOCKED
                    """, (rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)}, workspaceId, groupId, limit);
            int count = 0;
            for (UUID[] row : events)
                if (finishLocked(new GroupDispatchEnvelope(row[0], row[1], workspaceId, groupId), row[2], "NEEDS_REVIEW", null, "EXECUTION_UNCERTAIN"))
                    count++;
            return count;
        }));
    }

    // Exhausted broker delivery with no recorded consumer reservation: fence it for review.
    int reviewDead(UUID workspaceId, UUID groupId, int limit) {
        if (workspaceId == null || groupId == null || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid review scope");
        return Objects.requireNonNull(tx.execute(status -> db.update("""
                WITH picked AS (
                    SELECT o.event_id,o.task_id FROM group_dispatch_outbox o
                    JOIN group_publishing_tasks t ON t.id=o.task_id
                    JOIN facebook_groups g ON g.id=t.facebook_group_id
                    JOIN posts p ON p.id=t.post_id AND p.workspace_id=g.workspace_id
                    WHERE o.state='DEAD' AND t.status='PROCESSING'
                      AND g.workspace_id=? AND g.id=?
                      AND NOT EXISTS (SELECT 1 FROM group_dispatch_inbox i WHERE i.event_id=o.event_id)
                    ORDER BY o.event_id LIMIT ? FOR UPDATE OF t SKIP LOCKED
                ), recorded AS (
                    INSERT INTO group_dispatch_inbox(event_id,task_id,state,execution_token,review_after,completed_at,result_code)
                    SELECT event_id,task_id,'NEEDS_REVIEW',gen_random_uuid(),now(),now(),'BROKER_DELIVERY_UNCERTAIN' FROM picked
                    ON CONFLICT DO NOTHING RETURNING task_id
                ) UPDATE group_publishing_tasks t SET status='NEEDS_REVIEW'
                  FROM recorded WHERE t.id=recorded.task_id AND t.status='PROCESSING'
                """, workspaceId, groupId, limit)));
    }
}
