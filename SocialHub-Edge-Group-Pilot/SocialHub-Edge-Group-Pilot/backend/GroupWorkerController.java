package com.socialhub.grouppublishingtask.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/internal/group-worker")
@RequiredArgsConstructor
public class GroupWorkerController {
    private final JdbcTemplate db;
    @Value("${app.group-worker-token:${GROUP_WORKER_TOKEN:}}")
    private String workerToken;

    // One exact task for the controlled Group AUTO pilot. Blank = no claims.
    @Value("${GROUP_WORKER_TARGET_TASK_ID:}")
    private String targetTaskId;

    // Exact numeric Facebook Group ID for this pilot; blank disables claiming.
    @Value("${GROUP_WORKER_EXPECTED_GROUP_ID:}")
    private String expectedGroupId;

    private void check(String authorization) {
        if (workerToken.isBlank() || authorization == null || !MessageDigest.isEqual(
                ("Bearer " + workerToken).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8)))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    public record Task(UUID id, String groupUrl, String content,
                       List<String> imageUrls, int unsupportedMediaCount) {
    }

    public record Result(String status, String publishedUrl, String note) {
    }

    public record Preview(UUID id, String groupUrl, String content,
                          String taskStatus, String postStatus, String approvalStatus,
                          boolean due, int imageCount, int unsupportedMediaCount) {
    }

    // Read-only guard: safe even while another publisher is running.
    @GetMapping("/preview")
    public ResponseEntity<Preview> preview(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        check(authorization);
        if (expectedGroupId == null || !expectedGroupId.matches("[0-9]+"))
            return ResponseEntity.noContent().build();
        UUID exactTask;
        try {
            exactTask = UUID.fromString(targetTaskId.trim());
        } catch (Exception ignored) {
            return ResponseEntity.noContent().build();
        }
        List<Preview> rows = db.query("""
                SELECT t.id, g.group_url, p.content,
                       t.status AS task_status, p.status AS post_status,
                       p.approval_status AS approval_status,
                       (COALESCE(t.scheduled_at, t.created_at)<=now()) AS due,
                       (SELECT count(*) FROM post_media pm WHERE pm.post_id=t.post_id
                         AND pm.media_type='IMAGE') AS image_count,
                       (SELECT count(*) FROM post_media pm WHERE pm.post_id=t.post_id
                         AND pm.media_type NOT IN ('IMAGE')) AS unsupported_media_count
                FROM group_publishing_tasks t
                JOIN posts p ON p.id=t.post_id
                JOIN facebook_groups g ON g.id=t.facebook_group_id
                WHERE t.id=? AND g.external_group_id=?
                """, (rs, row) -> new Preview(
                rs.getObject("id", UUID.class), rs.getString("group_url"),
                rs.getString("content"), rs.getString("task_status"),
                rs.getString("post_status"), rs.getString("approval_status"),
                rs.getBoolean("due"), rs.getInt("image_count"),
                rs.getInt("unsupported_media_count")), exactTask, expectedGroupId);
        return rows.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(rows.getFirst());
    }

    @PostMapping("/claim")
    public ResponseEntity<Task> claim(@RequestHeader(value = "Authorization", required = false) String authorization) {
        check(authorization);
        if (expectedGroupId == null || !expectedGroupId.matches("[0-9]+")) {
            return ResponseEntity.noContent().build();
        }
        UUID exactTask;
        try {
            exactTask = UUID.fromString(targetTaskId.trim());
        } catch (Exception invalidOrBlank) {
            // Never claim arbitrary tasks: require one exact approved READY task ID.
            return ResponseEntity.noContent().build();
        }
        var rows = db.query("""
                WITH picked AS (
                  SELECT t.id FROM group_publishing_tasks t
                  JOIN posts p ON p.id=t.post_id
                  JOIN facebook_groups g ON g.id=t.facebook_group_id
                  WHERE t.id=? AND g.external_group_id=? AND t.status='READY'
                    AND p.status IN ('READY','SCHEDULED')
                    AND p.approval_status='APPROVED'
                    AND COALESCE(t.scheduled_at,t.created_at)<=now()
                  FOR UPDATE OF t SKIP LOCKED LIMIT 1
                ) UPDATE group_publishing_tasks t SET status='PROCESSING'
                  FROM picked WHERE t.id=picked.id RETURNING t.id
                """, (rs, n) -> rs.getObject(1, UUID.class), exactTask, expectedGroupId);
        if (rows.isEmpty()) return ResponseEntity.noContent().build();
        UUID id = rows.getFirst();
        List<String> images = db.query("""
                SELECT COALESCE(NULLIF(TRIM(pm.public_url),''), NULLIF(TRIM(pm.source_url),''))
                FROM post_media pm
                JOIN group_publishing_tasks t ON t.post_id=pm.post_id
                WHERE t.id=? AND pm.media_type='IMAGE'
                ORDER BY pm.display_order, pm.created_at
                """, (rs, n) -> rs.getString(1), id);
        Integer unsupported = db.queryForObject("""
                SELECT COUNT(*) FROM post_media pm
                JOIN group_publishing_tasks t ON t.post_id=pm.post_id
                WHERE t.id=? AND pm.media_type NOT IN ('IMAGE')
                """, Integer.class, id);
        return ResponseEntity.ok(db.queryForObject("""
                SELECT t.id,g.group_url,p.content FROM group_publishing_tasks t
                JOIN facebook_groups g ON g.id=t.facebook_group_id
                JOIN posts p ON p.id=t.post_id WHERE t.id=?
                """, (rs, n) -> new Task(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), images, unsupported == null ? 0 : unsupported), id));
    }

    @PostMapping("/{id}/result")
    public void result(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable UUID id, @RequestBody Result r) {
        check(authorization);
        if (!List.of("NEEDS_REVIEW", "FAILED", "PUBLISHED").contains(r.status()))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status");
        if ("PUBLISHED".equals(r.status()) && (r.publishedUrl() == null || !r.publishedUrl().startsWith("https://www.facebook.com/")))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Verified Facebook post URL required");
        int count = db.update("UPDATE group_publishing_tasks SET status=?,published_url=?,published_at=CASE WHEN ?='PUBLISHED' THEN now() ELSE NULL END WHERE id=? AND status='PROCESSING'", r.status(), r.publishedUrl(), r.status(), id);
        if (count != 1) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT);
    }
}
