package com.socialhub.grouppublishingtask.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/**
 * ONE-task controlled Group video/MP4 pilot: only explicit LIVE may claim.
 * Facebook's resulting post may be presented as a Reel. This class does not
 * select a Reel creation surface, schedule batches, or publish by itself.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "app.group.legacy-pilot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RestController
@RequestMapping("/internal/group-worker")
@RequiredArgsConstructor
public class GroupWorkerVideoPilotController {
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

    public record VideoClaim(UUID id, String groupUrl, String content,
                             List<String> imageUrls, List<String> videoUrls,
                             int unsupportedMediaCount) {
    }

    @Transactional
    @PostMapping("/video-claim")
    public ResponseEntity<VideoClaim> claim(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticate(authorization);
        if (expectedGroupId == null || !expectedGroupId.matches("[0-9]+")) {
            return ResponseEntity.noContent().build();
        }
        final UUID id;
        try {
            id = UUID.fromString(targetTaskId.trim());
        } catch (RuntimeException invalidId) {
            return ResponseEntity.noContent().build();
        }
        // One media row ONLY, explicitly VIDEO or REEL. No mixed media.
        // Workspace and approval must still match at the moment of claim.
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
                    AND (SELECT COUNT(*) FROM post_media pm WHERE pm.post_id=t.post_id)=1
                    AND EXISTS (
                      SELECT 1 FROM post_media pm
                      WHERE pm.post_id=t.post_id AND pm.media_type IN ('VIDEO','REEL')
                        AND COALESCE(NULLIF(TRIM(pm.public_url),''),
                                     NULLIF(TRIM(pm.source_url),'')) IS NOT NULL
                    )
                  FOR UPDATE OF t SKIP LOCKED LIMIT 1
                )
                UPDATE group_publishing_tasks t SET status='PROCESSING'
                FROM picked WHERE t.id=picked.id RETURNING t.id
                """, (rs, n) -> rs.getObject(1, UUID.class), id, expectedGroupId);
        if (claimed.isEmpty()) return ResponseEntity.noContent().build();

        List<String> videos = db.query("""
                SELECT COALESCE(NULLIF(TRIM(pm.public_url),''),
                                NULLIF(TRIM(pm.source_url),'')) AS url
                FROM post_media pm JOIN group_publishing_tasks t ON t.post_id=pm.post_id
                WHERE t.id=? AND pm.media_type IN ('VIDEO','REEL')
                ORDER BY pm.display_order, pm.created_at, pm.id
                """, (rs, n) -> rs.getString("url"), id);

        return ResponseEntity.ok(db.queryForObject("""
                SELECT t.id,g.group_url,p.content FROM group_publishing_tasks t
                JOIN facebook_groups g ON g.id=t.facebook_group_id
                JOIN posts p ON p.id=t.post_id WHERE t.id=?
                """, (rs, n) -> new VideoClaim(
                rs.getObject("id", UUID.class), rs.getString("group_url"),
                rs.getString("content"), List.of(), videos, 0), id));
    }
}
