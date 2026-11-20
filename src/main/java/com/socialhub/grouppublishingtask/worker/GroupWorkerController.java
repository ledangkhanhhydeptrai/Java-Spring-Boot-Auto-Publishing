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
    @Value("${app.group-worker-token:}")
    private String workerToken;

    private void check(String authorization) {
        if (workerToken.isBlank() || authorization == null || !MessageDigest.isEqual(
                ("Bearer " + workerToken).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8)))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    public record Task(UUID id, String groupUrl, String content) {
    }

    public record Result(String status, String publishedUrl, String note) {
    }

    @PostMapping("/claim")
    public ResponseEntity<Task> claim(@RequestHeader(value = "Authorization", required = false) String authorization) {
        check(authorization);
        var rows = db.query("""
                WITH picked AS (
                  SELECT t.id FROM group_publishing_tasks t
                  JOIN posts p ON p.id=t.post_id
                  WHERE t.status='PENDING' AND p.status IN ('READY','SCHEDULED')
                    AND COALESCE(t.scheduled_at, t.created_at)<=now()
                  ORDER BY COALESCE(t.scheduled_at,t.created_at)
                  FOR UPDATE OF t SKIP LOCKED LIMIT 1
                ) UPDATE group_publishing_tasks t SET status='PROCESSING'
                  FROM picked WHERE t.id=picked.id RETURNING t.id
                """, (rs, n) -> rs.getObject(1, UUID.class));
        if (rows.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(db.queryForObject("""
                SELECT t.id,g.group_url,p.content FROM group_publishing_tasks t
                JOIN facebook_groups g ON g.id=t.facebook_group_id
                JOIN posts p ON p.id=t.post_id WHERE t.id=?
                """, (rs, n) -> new Task(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), rows.getFirst()));
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
