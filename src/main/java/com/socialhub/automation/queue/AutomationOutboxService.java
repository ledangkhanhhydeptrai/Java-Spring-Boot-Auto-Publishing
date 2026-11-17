package com.socialhub.automation.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Candidate discovery never claims a publishing job or calls a public platform API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationOutboxService {
    private final JdbcTemplate db;

    @Value("${app.automation.queue.start-after:}")
    private String startAfter;

    @Value("${app.automation.queue.max-per-scan:25}")
    private int maxPerScan;

    public int stageDueJobs() {
        if (startAfter == null || startAfter.isBlank()) {
            log.warn("[AUTOMATION] start-after missing; refusing to scan legacy jobs");
            return 0;
        }
        final Instant cutoff;
        try {
            cutoff = Instant.parse(startAfter);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("Invalid app.automation.queue.start-after; use ISO-8601 UTC", e);
        }
        if (cutoff.isAfter(Instant.now())) return 0;
        int limit = Math.max(1, Math.min(maxPerScan, 100));
        // LIVE eligibility is intentionally conservative and first-attempt-only.
        // Do not import old pending jobs, retries, or long overdue jobs.
        List<UUID> ids = db.query("""
                SELECT j.id FROM publishing_jobs j
                JOIN posts p ON p.id=j.post_id
                JOIN social_accounts a ON a.id=j.social_account_id
                WHERE j.status='PENDING' AND j.attempt_count=0
                  AND j.external_post_id IS NULL AND j.published_at IS NULL
                  AND j.published_url IS NULL
                  AND a.platform IN ('FACEBOOK_PAGE','THREADS')
                  AND a.workspace_id=p.workspace_id
                  AND a.status='CONNECTED'
                  AND NULLIF(BTRIM(a.access_token_encrypted),'') IS NOT NULL
                  AND (a.token_expires_at IS NULL OR a.token_expires_at > NOW())
                  AND p.status IN ('READY','SCHEDULED')
                  AND p.approval_status='APPROVED'
                  AND j.created_at >= ?
                  AND COALESCE(j.scheduled_at,j.created_at) <= NOW()
                  AND COALESCE(j.scheduled_at,j.created_at) >= NOW()-INTERVAL '5 minutes'
                  AND NOT EXISTS (
                      SELECT 1 FROM post_media m WHERE m.post_id=p.id
                        AND (m.media_type IS DISTINCT FROM 'IMAGE'
                          OR NULLIF(BTRIM(m.public_url),'') IS NULL)
                  )
                  AND (SELECT COUNT(*) FROM post_media m WHERE m.post_id=p.id) <= 20
                  AND (
                      NOT EXISTS (SELECT 1 FROM campaign_jobs cj WHERE cj.publishing_job_id=j.id)
                      OR EXISTS (
                          SELECT 1 FROM campaign_jobs cj JOIN campaigns c ON c.id=cj.campaign_id
                          WHERE cj.publishing_job_id=j.id AND c.status='RUNNING'
                      )
                  )
                  AND NOT EXISTS (SELECT 1 FROM automation_job_outbox o WHERE o.publishing_job_id=j.id)
                ORDER BY COALESCE(j.scheduled_at,j.created_at),j.created_at
                LIMIT ?
                """, (rs, i) -> rs.getObject(1, UUID.class), java.sql.Timestamp.from(cutoff), limit);
        int staged = 0;
        for (UUID id : ids) {
            staged += db.update("""
                    INSERT INTO automation_job_outbox(publishing_job_id,platform)
                    SELECT j.id,a.platform
                    FROM publishing_jobs j
                    JOIN social_accounts a ON a.id=j.social_account_id
                    WHERE j.id=? AND j.status='PENDING' AND j.attempt_count=0
                      AND j.external_post_id IS NULL AND j.published_at IS NULL
                      AND j.published_url IS NULL
                    ON CONFLICT (publishing_job_id) DO NOTHING
                    """, id);
        }
        return staged;
    }
}
