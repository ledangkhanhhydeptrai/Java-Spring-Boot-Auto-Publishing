
package com.socialhub.automation.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Candidate discovery only.
 * <p>
 * Does not claim publishing_jobs or call Meta.
 * <p>
 * OBSERVE:
 * Stages eligible Facebook and Threads jobs.
 * <p>
 * LIVE:
 * Stages only one explicitly allowed Facebook Job
 * belonging to one explicitly allowed Facebook Page.
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

    @Value("${app.automation.queue.live-dispatch-enabled:false}")
    private boolean liveDispatchEnabled;

    @Value("${app.automation.live.enabled:false}")
    private boolean liveEnabled;

    @Value("${FACEBOOK_LIVE_JOB_ID:}")
    private String allowedJobId;

    @Value("${FACEBOOK_LIVE_ALLOWED_PAGE_ID:}")
    private String allowedPageId;

    @Value("${FACEBOOK_MAX_IMAGES_PER_POST:10}")
    private int maxImagesPerPost;

    public int stageDueJobs() {

        // 1. Require a valid cutoff.
        if (startAfter == null || startAfter.isBlank()) {
            log.warn(
                    "[AUTOMATION] start-after missing; refusing to scan legacy jobs"
            );
            return 0;
        }

        final Instant cutoff;

        try {
            cutoff = Instant.parse(startAfter.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException(
                    "Invalid app.automation.queue.start-after; use ISO-8601 UTC",
                    ex
            );
        }

        if (cutoff.isAfter(Instant.now())) {
            return 0;
        }

        // 2. Validate the LIVE allowlist.
        final UUID liveJobId;
        final String livePageId;

        if (liveDispatchEnabled) {

            if (!liveEnabled) {
                throw new IllegalStateException(
                        "LIVE dispatch requires app.automation.live.enabled=true"
                );
            }

            if (allowedJobId == null || allowedJobId.isBlank()) {
                throw new IllegalStateException(
                        "LIVE requires FACEBOOK_LIVE_JOB_ID"
                );
            }

            if (allowedPageId == null
                    || !allowedPageId.trim().matches("[0-9]+")) {
                throw new IllegalStateException(
                        "LIVE requires a numeric FACEBOOK_LIVE_ALLOWED_PAGE_ID"
                );
            }

            try {
                liveJobId = UUID.fromString(
                        allowedJobId.trim()
                );
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                        "Invalid FACEBOOK_LIVE_JOB_ID",
                        ex
                );
            }

            livePageId = allowedPageId.trim();

        } else {
            liveJobId = null;
            livePageId = "";
        }

        // 3. Limit batch size and align LIVE media cap with Worker.
        int limit = Math.max(
                1,
                Math.min(maxPerScan, 100)
        );

        if (liveDispatchEnabled) {
            limit = 1;
        }

        if (liveDispatchEnabled && maxImagesPerPost < 1) {
            throw new IllegalStateException(
                    "FACEBOOK_MAX_IMAGES_PER_POST must be positive"
            );
        }

        int imageLimit = liveDispatchEnabled
                ? Math.min(maxImagesPerPost, 20)
                : 20;

        Timestamp cutoffTimestamp = Timestamp.from(cutoff);

        // 4. Discover eligible jobs.
        List<UUID> ids = db.query(
                """
                        SELECT j.id
                        FROM publishing_jobs j
                        
                        JOIN posts p
                            ON p.id = j.post_id
                        
                        JOIN social_accounts a
                            ON a.id = j.social_account_id
                        
                        WHERE j.status = 'PENDING'
                          AND j.attempt_count = 0
                        
                          AND j.external_post_id IS NULL
                          AND j.published_at IS NULL
                          AND j.published_url IS NULL
                        
                          AND a.platform IN ('FACEBOOK_PAGE', 'THREADS')
                          AND a.workspace_id = p.workspace_id
                          AND a.status = 'CONNECTED'
                        
                          AND NULLIF(
                              BTRIM(a.access_token_encrypted), ''
                          ) IS NOT NULL
                        
                          AND (
                              a.token_expires_at IS NULL
                              OR a.token_expires_at > NOW()
                          )
                        
                          AND p.status IN ('READY', 'SCHEDULED')
                          AND p.approval_status = 'APPROVED'
                        
                          AND NULLIF(BTRIM(p.content), '') IS NOT NULL
                        
                          AND j.created_at >= ?
                        
                          AND COALESCE(j.scheduled_at, j.created_at) <= NOW()
                        
                          AND COALESCE(j.scheduled_at, j.created_at)
                              >= NOW() - INTERVAL '5 minutes'
                        
                          AND NOT EXISTS (
                              SELECT 1
                              FROM post_media m
                              WHERE m.post_id = p.id
                                AND (
                                    m.media_type IS DISTINCT FROM 'IMAGE'
                                    OR NULLIF(BTRIM(m.public_url), '') IS NULL
                                )
                          )
                        
                          AND (
                              SELECT COUNT(*)
                              FROM post_media m
                              WHERE m.post_id = p.id
                          ) <= ?
                        
                          AND (
                              NOT EXISTS (
                                  SELECT 1
                                  FROM campaign_jobs cj
                                  WHERE cj.publishing_job_id = j.id
                              )
                              OR EXISTS (
                                  SELECT 1
                                  FROM campaign_jobs cj
                                  JOIN campaigns c
                                      ON c.id = cj.campaign_id
                                  WHERE cj.publishing_job_id = j.id
                                    AND c.status = 'RUNNING'
                              )
                          )
                        
                          AND NOT EXISTS (
                              SELECT 1
                              FROM facebook_bulk_run_jobs bj
                              WHERE bj.publishing_job_id = j.id
                          )
                        
                          AND (
                              CAST(? AS BOOLEAN) = FALSE
                              OR (
                                  j.id = CAST(? AS UUID)
                                  AND a.platform = 'FACEBOOK_PAGE'
                                  AND a.external_account_id = ?
                              )
                          )
                        
                          AND NOT EXISTS (
                              SELECT 1
                              FROM automation_job_outbox o
                              WHERE o.publishing_job_id = j.id
                          )
                        
                        ORDER BY
                            COALESCE(j.scheduled_at, j.created_at),
                            j.created_at
                        
                        LIMIT ?
                        """,
                (rs, rowNum) ->
                        rs.getObject(1, UUID.class),
                cutoffTimestamp,
                imageLimit,
                liveDispatchEnabled,
                liveJobId,
                livePageId,
                limit
        );

        // 5. Stage eligible jobs with a second eligibility check.
        int staged = 0;

        for (UUID id : ids) {

            staged += db.update(
                    """
                            INSERT INTO automation_job_outbox (
                                publishing_job_id,
                                platform
                            )
                            
                            SELECT j.id, a.platform
                            
                            FROM publishing_jobs j
                            
                            JOIN posts p
                                ON p.id = j.post_id
                            
                            JOIN social_accounts a
                                ON a.id = j.social_account_id
                            
                            WHERE j.id = ?
                            
                              AND j.status = 'PENDING'
                              AND j.attempt_count = 0
                            
                              AND j.external_post_id IS NULL
                              AND j.published_at IS NULL
                              AND j.published_url IS NULL
                            
                              AND a.platform IN ('FACEBOOK_PAGE', 'THREADS')
                              AND a.workspace_id = p.workspace_id
                              AND a.status = 'CONNECTED'
                            
                              AND NULLIF(
                                  BTRIM(a.access_token_encrypted), ''
                              ) IS NOT NULL
                            
                              AND (
                                  a.token_expires_at IS NULL
                                  OR a.token_expires_at > NOW()
                              )
                            
                              AND p.status IN ('READY', 'SCHEDULED')
                              AND p.approval_status = 'APPROVED'
                            
                              AND NULLIF(BTRIM(p.content), '') IS NOT NULL
                            
                              AND j.created_at >= ?
                            
                              AND COALESCE(j.scheduled_at, j.created_at)
                                  <= NOW()
                            
                              AND COALESCE(j.scheduled_at, j.created_at)
                                  >= NOW() - INTERVAL '5 minutes'
                            
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM post_media m
                                  WHERE m.post_id = p.id
                                    AND (
                                        m.media_type IS DISTINCT FROM 'IMAGE'
                                        OR NULLIF(BTRIM(m.public_url), '') IS NULL
                                    )
                              )
                            
                              AND (
                                  SELECT COUNT(*)
                                  FROM post_media m
                                  WHERE m.post_id = p.id
                              ) <= ?
                            
                              AND (
                                  NOT EXISTS (
                                      SELECT 1
                                      FROM campaign_jobs cj
                                      WHERE cj.publishing_job_id = j.id
                                  )
                                  OR EXISTS (
                                      SELECT 1
                                      FROM campaign_jobs cj
                                      JOIN campaigns c
                                          ON c.id = cj.campaign_id
                                      WHERE cj.publishing_job_id = j.id
                                        AND c.status = 'RUNNING'
                                  )
                              )
                            
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM facebook_bulk_run_jobs bj
                                  WHERE bj.publishing_job_id = j.id
                              )
                            
                              AND (
                                  CAST(? AS BOOLEAN) = FALSE
                                  OR (
                                      j.id = CAST(? AS UUID)
                                      AND a.platform = 'FACEBOOK_PAGE'
                                      AND a.external_account_id = ?
                                  )
                              )
                            
                            ON CONFLICT (publishing_job_id)
                            DO NOTHING
                            """,
                    id,
                    cutoffTimestamp,
                    imageLimit,
                    liveDispatchEnabled,
                    liveJobId,
                    livePageId
            );
        }

        if (staged > 0) {
            log.info(
                    "[AUTOMATION] Staged {} outbox event(s); liveDispatch={}",
                    staged,
                    liveDispatchEnabled
            );
        }

        return staged;
    }
}
