package com.socialhub.grouppublishingtask.dispatch;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * INACTIVE foundation. Deliberately NOT a Spring bean, scheduler or endpoint.
 * A future authorized integration must first unify all legacy task writers,
 * enforce provider authorization and verify payload eligibility. Do not wire yet.
 * One SQL statement atomically claims READY tasks and inserts their outbox events.
 */
final class GroupDispatchClaimStore {
    private final JdbcTemplate db;

    GroupDispatchClaimStore(JdbcTemplate db) {
        this.db = db;
    }

    record Staged(UUID eventId, UUID taskId) {
    }

    List<Staged> claimAndStage(UUID workspaceId, UUID groupId, int limit) {
        return claimAndStage(workspaceId, groupId, limit, null);
    }

    List<Staged> claimAndStage(UUID workspaceId, UUID groupId, int limit, UUID exactTask) {
        if (workspaceId == null || groupId == null || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Valid scope and limit 1..100 required");
        }
        return db.query("""
                        WITH picked AS (
                            SELECT t.id
                            FROM group_publishing_tasks t
                            JOIN facebook_groups g ON g.id = t.facebook_group_id
                            JOIN posts p ON p.id = t.post_id AND p.workspace_id = g.workspace_id
                            WHERE g.workspace_id = ? AND g.id = ?
                              AND (CAST(? AS uuid) IS NULL OR t.id=?)
                              AND t.status = 'READY' AND t.scheduled_at <= now()
                              AND p.approval_status = 'APPROVED'
                              AND p.status IN ('READY','SCHEDULED','PUBLISHED')
                              AND NOT EXISTS (SELECT 1 FROM group_dispatch_outbox o WHERE o.task_id = t.id)
                            ORDER BY t.scheduled_at, t.id
                            LIMIT ? FOR UPDATE OF t SKIP LOCKED
                        ), claimed AS (
                            UPDATE group_publishing_tasks t SET status = 'PROCESSING'
                            FROM picked WHERE t.id = picked.id AND t.status = 'READY'
                            RETURNING t.id
                        )
                        INSERT INTO group_dispatch_outbox (event_id, task_id)
                        SELECT gen_random_uuid(), id FROM claimed
                        RETURNING event_id, task_id
                        """, (rs, n) -> new Staged(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                workspaceId, groupId, exactTask, exactTask, limit);
    }
}
