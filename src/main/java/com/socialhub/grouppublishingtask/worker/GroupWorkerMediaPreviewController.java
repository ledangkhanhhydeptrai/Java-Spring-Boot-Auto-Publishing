package com.socialhub.grouppublishingtask.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/**
 * Separate image pilot endpoints for ONE exact Group Task.
 * GET is read-only. POST performs an atomic READY -> PROCESSING claim
 * only when the operator explicitly confirms LIVE in Edge.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "app.group.legacy-pilot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RestController
@RequestMapping("/internal/group-worker")
@RequiredArgsConstructor
public class GroupWorkerMediaPreviewController {
    private final JdbcTemplate db;

    @Value("${app.group-worker-token:${GROUP_WORKER_TOKEN:}}")
    private String workerToken;

    @Value("${GROUP_WORKER_TARGET_TASK_ID:}")
    private String targetTaskId;

    @Value("${GROUP_WORKER_EXPECTED_GROUP_ID:}")
    private String expectedGroupId;

    private void authenticate(String authorization) {
        String token = workerToken == null ? "" : workerToken;
        if (token.isBlank() || authorization == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * Read only for one EXACT task, including PENDING/future tasks. Unlike the
     * publisher /preview this endpoint must never require READY or due.
     * It does not claim, approve, move scheduling or publish anything.
     */
    public record Inspection(UUID id, String groupUrl, String content,
                             String taskStatus, String postStatus, String approvalStatus,
                             boolean due, int imageCount, int unsupportedMediaCount) {
    }

    @GetMapping("/inspect-exact")
    public ResponseEntity<Inspection> inspectExact(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticate(authorization);
        if (expectedGroupId == null || !expectedGroupId.matches("[0-9]+"))
            return ResponseEntity.noContent().build();
        final UUID exactId;
        try {
            exactId = UUID.fromString(targetTaskId.trim());
        } catch (RuntimeException badId) {
            return ResponseEntity.noContent().build();
        }
        var result = db.query("""
                SELECT t.id, g.group_url, p.content, t.status AS task_status,
                       p.status AS post_status, p.approval_status,
                       (COALESCE(t.scheduled_at, t.created_at) <= NOW()) AS due,
                       (SELECT COUNT(*) FROM post_media pm WHERE pm.post_id=t.post_id
                          AND pm.media_type='IMAGE') AS image_count,
                       (SELECT COUNT(*) FROM post_media pm WHERE pm.post_id=t.post_id
                          AND pm.media_type IS DISTINCT FROM 'IMAGE') AS unsupported_count
                  FROM group_publishing_tasks t
                  JOIN posts p ON p.id=t.post_id
                  JOIN facebook_groups g ON g.id=t.facebook_group_id
                 WHERE t.id=? AND g.external_group_id=? AND p.workspace_id=g.workspace_id
                """, (rs, n) -> new Inspection(
                rs.getObject("id", UUID.class), rs.getString("group_url"),
                rs.getString("content"), rs.getString("task_status"),
                rs.getString("post_status"), rs.getString("approval_status"),
                rs.getBoolean("due"), rs.getInt("image_count"),
                rs.getInt("unsupported_count")), exactId, expectedGroupId);
        return result.size() == 1 ? ResponseEntity.ok(result.getFirst())
                : ResponseEntity.noContent().build();
    }

    public record Media(String type, String url) {
    }

    public record Manifest(UUID id, String groupId, List<Media> media) {
    }

    @GetMapping("/image-manifest")
    public ResponseEntity<Manifest> manifest(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticate(authorization);
        if (expectedGroupId == null || !expectedGroupId.matches("[0-9]+")) {
            return ResponseEntity.noContent().build();
        }
        final UUID exactId;
        try {
            exactId = UUID.fromString(targetTaskId.trim());
        } catch (RuntimeException badId) {
            return ResponseEntity.noContent().build();
        }
        // Confirm group before returning any URLs. Post ID is never caller-controlled.
        Integer eligible = db.queryForObject("""
                SELECT count(*) FROM group_publishing_tasks t
                JOIN facebook_groups g ON g.id = t.facebook_group_id
                WHERE t.id = ? AND g.external_group_id = ?
                """, Integer.class, exactId, expectedGroupId);
        if (eligible == null || eligible != 1) {
            return ResponseEntity.noContent().build();
        }
        List<Media> rows = db.query("""
                        SELECT pm.media_type,
                               COALESCE(NULLIF(TRIM(pm.public_url), ''),
                                        NULLIF(TRIM(pm.source_url), '')) AS url
                        FROM post_media pm
                        JOIN group_publishing_tasks t ON t.post_id = pm.post_id
                        JOIN facebook_groups g ON g.id = t.facebook_group_id
                        WHERE t.id = ? AND g.external_group_id = ?
                        ORDER BY pm.display_order, pm.created_at, pm.id
                        """, (rs, n) -> new Media(rs.getString("media_type"), rs.getString("url")),
                exactId, expectedGroupId);
        return ResponseEntity.ok(new Manifest(exactId, expectedGroupId, rows));
    }

    /**
     * One-task image-specific atomic claim. The existing text-only claim route
     * remains unchanged. A Post already PUBLISHED to a Page/Threads is allowed,
     * because its Group Task has a separate status and approval lifecycle.
     */
    public record ImageClaim(UUID id, String groupUrl, String content,
                             List<String> imageUrls, int unsupportedMediaCount) {
    }

    @Transactional
    @PostMapping("/image-claim")
    public ResponseEntity<ImageClaim> claimImage(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticate(authorization);
        if (expectedGroupId == null || !expectedGroupId.matches("[0-9]+")) {
            return ResponseEntity.noContent().build();
        }
        final UUID exactId;
        try {
            exactId = UUID.fromString(targetTaskId.trim());
        } catch (RuntimeException badId) {
            return ResponseEntity.noContent().build();
        }
        List<UUID> claimed = db.query("""
                WITH picked AS (
                    SELECT t.id FROM group_publishing_tasks t
                    JOIN posts p ON p.id=t.post_id
                    JOIN facebook_groups g ON g.id=t.facebook_group_id
                    WHERE t.id=? AND g.external_group_id=? AND t.status='READY'
                    AND NOT EXISTS (SELECT 1 FROM group_dispatch_outbox o WHERE o.task_id=t.id)
                      AND p.workspace_id=g.workspace_id
                      AND p.status IN ('READY','SCHEDULED','PUBLISHED')
                      AND p.approval_status='APPROVED'
                      AND COALESCE(t.scheduled_at,t.created_at)<=now()
                      AND (SELECT COUNT(*) FROM post_media pm
                            WHERE pm.post_id=t.post_id AND pm.media_type='IMAGE') BETWEEN 1 AND 10
                      AND NOT EXISTS (SELECT 1 FROM post_media pm
                            WHERE pm.post_id=t.post_id AND (pm.media_type IS DISTINCT FROM 'IMAGE'
                                OR COALESCE(NULLIF(TRIM(pm.public_url),''),
                                            NULLIF(TRIM(pm.source_url),'')) IS NULL))
                    FOR UPDATE OF t SKIP LOCKED LIMIT 1
                )
                UPDATE group_publishing_tasks t SET status='PROCESSING'
                FROM picked WHERE t.id=picked.id RETURNING t.id
                """, (rs, row) -> rs.getObject(1, UUID.class), exactId, expectedGroupId);
        if (claimed.isEmpty()) return ResponseEntity.noContent().build();
        List<Media> media = db.query("""
                SELECT pm.media_type,
                       COALESCE(NULLIF(TRIM(pm.public_url),''),NULLIF(TRIM(pm.source_url),'')) AS url
                FROM post_media pm
                JOIN group_publishing_tasks t ON t.post_id=pm.post_id
                WHERE t.id=? ORDER BY pm.display_order,pm.created_at,pm.id
                """, (rs, row) -> new Media(rs.getString("media_type"), rs.getString("url")), exactId);
        return ResponseEntity.ok(db.queryForObject("""
                SELECT t.id,g.group_url,p.content FROM group_publishing_tasks t
                JOIN facebook_groups g ON g.id=t.facebook_group_id
                JOIN posts p ON p.id=t.post_id WHERE t.id=?
                """, (rs, row) -> new ImageClaim(rs.getObject("id", UUID.class),
                rs.getString("group_url"), rs.getString("content"),
                media.stream().map(Media::url).toList(), 0), exactId));
    }

}
