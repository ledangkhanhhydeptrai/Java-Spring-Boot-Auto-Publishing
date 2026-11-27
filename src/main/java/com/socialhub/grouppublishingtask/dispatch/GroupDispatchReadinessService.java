package com.socialhub.grouppublishingtask.dispatch;

import com.socialhub.grouppublishingtask.automation.GroupTaskQueueService;
import com.socialhub.security.AccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Phase 1 is strictly read-only. No claim store is instantiated.
 */
@Service
public class GroupDispatchReadinessService {
    private final JdbcTemplate db;
    private final AccessService access;
    private final GroupTaskQueueService tasks;
    private final boolean legacyPilotEnabled;

    public GroupDispatchReadinessService(JdbcTemplate db, AccessService access, GroupTaskQueueService tasks,
                                         @org.springframework.beans.factory.annotation.Value("${app.group.legacy-pilot.enabled:false}") boolean legacyPilotEnabled) {
        this.db = db;
        this.access = access;
        this.tasks = tasks;
        this.legacyPilotEnabled = legacyPilotEnabled;
    }

    public record Status(UUID workspaceId, UUID facebookGroupId, String state,
                         boolean dispatchEnabled, long readyDueWithoutEvent,
                         long outboxPending, long outboxBrokerConfirmed, long outboxDead, long activeLeases,
                         long expiredLeases,
                         boolean legacyPilotEnabled) {
    }

    @Transactional(readOnly = true)
    public Status status(UUID workspaceId, UUID groupId) {
        access.workspace(workspaceId);
        tasks.counts(workspaceId, groupId); // existing scope validation, rejects cross-workspace group
        return db.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE o.event_id IS NULL
                             AND t.status = 'READY' AND t.scheduled_at <= now()
                             AND p.approval_status = 'APPROVED'
                             AND p.status IN ('READY','SCHEDULED','PUBLISHED')),
                       COUNT(*) FILTER (WHERE o.state = 'PENDING'),
                       COUNT(*) FILTER (WHERE o.state = 'BROKER_CONFIRMED'),
                       COUNT(*) FILTER (WHERE o.state = 'DEAD'),
                       COUNT(*) FILTER (WHERE o.state = 'PENDING' AND o.lease_until > now()),
                       COUNT(*) FILTER (WHERE o.state = 'PENDING' AND o.lease_until <= now())
                FROM group_publishing_tasks t
                JOIN facebook_groups g ON g.id = t.facebook_group_id
                JOIN posts p ON p.id = t.post_id AND p.workspace_id = g.workspace_id
                LEFT JOIN group_dispatch_outbox o ON o.task_id = t.id
                WHERE g.workspace_id = ? AND g.id = ?
                """, (rs, n) -> new Status(workspaceId, groupId, legacyPilotEnabled ? "BLOCKED_LEGACY_PILOT_ENABLED" : "BLOCKED_NO_AUTHORIZED_PUBLISHER",
                false, rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), legacyPilotEnabled), workspaceId, groupId);
    }
}
