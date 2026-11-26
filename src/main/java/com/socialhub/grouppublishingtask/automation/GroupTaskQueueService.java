package com.socialhub.grouppublishingtask.automation;

import com.socialhub.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Read-only view of the existing Group task queue; no competing claim or retry path. */
@Service
public class GroupTaskQueueService {
    private final JdbcTemplate db;

    public GroupTaskQueueService(JdbcTemplate db) { this.db = db; }

    public record Counts(long total, long pending, long ready, long dueReady,
                         long processing, long published, long failed, long needsReview,
                         long cancelled) { }

    public record DueTask(UUID taskId, UUID postId, OffsetDateTime scheduledAt) { }

    @Transactional(readOnly = true)
    public Counts counts(UUID workspaceId, UUID groupId) {
        requireGroup(workspaceId, groupId);
        return db.queryForObject("""
            SELECT COUNT(*),
                   COUNT(*) FILTER (WHERE t.status='PENDING'),
                   COUNT(*) FILTER (WHERE t.status='READY'),
                   COUNT(*) FILTER (WHERE t.status='READY'
                     AND t.scheduled_at<=now() AND p.approval_status='APPROVED'
                     AND p.status IN ('READY','SCHEDULED','PUBLISHED')),
                   COUNT(*) FILTER (WHERE t.status='PROCESSING'),
                   COUNT(*) FILTER (WHERE t.status='PUBLISHED'),
                   COUNT(*) FILTER (WHERE t.status='FAILED'),
                   COUNT(*) FILTER (WHERE t.status='NEEDS_REVIEW'),
                   COUNT(*) FILTER (WHERE t.status='CANCELLED')
              FROM group_publishing_tasks t
              JOIN facebook_groups g ON g.id=t.facebook_group_id
              JOIN posts p ON p.id=t.post_id AND p.workspace_id=g.workspace_id
             WHERE g.workspace_id=? AND g.id=?
            """, (rs,n) -> new Counts(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getLong(8),
                rs.getLong(9)), workspaceId, groupId);
    }

    @Transactional(readOnly = true)
    public List<DueTask> due(UUID workspaceId, UUID groupId, int limit) {
        requireGroup(workspaceId, groupId);
        if (limit < 1 || limit > 100)
            throw new ApiException(HttpStatus.BAD_REQUEST,"limit must be between 1 and 100");
        return db.query("""
            SELECT t.id,t.post_id,t.scheduled_at
              FROM group_publishing_tasks t
              JOIN facebook_groups g ON g.id=t.facebook_group_id
              JOIN posts p ON p.id=t.post_id AND p.workspace_id=g.workspace_id
             WHERE g.workspace_id=? AND g.id=? AND t.status='READY'
               AND t.scheduled_at<=now() AND p.approval_status='APPROVED'
               AND p.status IN ('READY','SCHEDULED','PUBLISHED')
             ORDER BY t.scheduled_at,t.id LIMIT ?
            """, (rs,n) -> new DueTask(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,OffsetDateTime.class)),workspaceId,groupId,limit);
    }

    private void requireGroup(UUID workspaceId, UUID groupId) {
        if (workspaceId == null || groupId == null)
            throw new ApiException(HttpStatus.BAD_REQUEST,"workspaceId and facebookGroupId required");
        Boolean valid = db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM facebook_groups WHERE workspace_id=? AND id=?)
            """,Boolean.class,workspaceId,groupId);
        if (!Boolean.TRUE.equals(valid))
            throw new ApiException(HttpStatus.NOT_FOUND,"Group not found in workspace");
    }
}
