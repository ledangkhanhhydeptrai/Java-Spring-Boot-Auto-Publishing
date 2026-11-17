package com.socialhub.publishingjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.facebookoauth.storage.FacebookTokenStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FanpageQueueProcessor {

    private static final int MAX_ATTEMPTS = 3;

    private static final String UNKNOWN_OUTCOME =
            "Meta publication outcome unknown. Manual verification required.";
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(
                    FanpageQueueProcessor.class
            );

    private static final String MOCK_URL = "http://127.0.0.1:9090";
    private static final String LIVE_URL = "https://graph.facebook.com";

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final FacebookTokenStorage tokenStorage;
    @Value("${META_GRAPH_VERSION:v23.0}")
    private String graphVersion;

    @Value("${PUBLISHER_BATCH_SIZE:5}")
    private int batchSize;

    @Value("${FACEBOOK_MULTI_JOB_ENABLED:false}")
    private boolean multiJobEnabled;

    @Value("${FACEBOOK_MULTI_JOB_NOT_BEFORE:}")
    private String multiJobNotBefore;

    @Value("${META_BASE_URL:http://127.0.0.1:9090}")
    private String metaBaseUrl;

    @Value("${FACEBOOK_PUBLISHING_MODE:MOCK}")
    private String publishingMode;

    @Value("${FACEBOOK_LIVE_JOB_ID:}")
    private String liveJobId;

    @Value("${FACEBOOK_BULK_ENABLED:false}")
    private boolean bulkEnabled;

    @Value("${FACEBOOK_MOCK_BULK_RUN_ID:}")
    private String mockBulkRunId;

    @Value("${FACEBOOK_MOCK_JOB_ID:}")
    private String mockJobId;

    @Value("${FACEBOOK_MOCK_PREFLIGHT_RETRY_ENABLED:false}")
    private boolean mockPreflightRetryEnabled;

    @Value("${FACEBOOK_MAX_IMAGES_PER_POST:10}")
    private int maxImagesPerPost;

    // Optional, Page-scoped multi-Facebook selection. Both values must be set together.
    // If unset, LIVE publishing continues using the previously tested legacy Page token.
    @Value("${FACEBOOK_LIVE_CONNECTION_PAGE_ID:}")
    private String liveConnectionPageId;

    @Value("${FACEBOOK_LIVE_CONNECTION_USER_ID:}")
    private String liveConnectionUserId;


    private record ClaimedJob(UUID id, int attempt) {
    }

    private boolean isLive() {
        return "LIVE".equalsIgnoreCase(publishingMode);
    }

    private boolean isBulkLive() {
        return isLive() && bulkEnabled;
    }

    private boolean isBulkProcessing() {
        return bulkEnabled && (isMock() || isLive());
    }

    private UUID targetMockBulkRunId() {
        if (!isMock() || !bulkEnabled) {
            return null;
        }
        if (mockBulkRunId == null || mockBulkRunId.isBlank()) {
            throw new IllegalStateException(
                    "MOCK Bulk requires FACEBOOK_MOCK_BULK_RUN_ID"
            );
        }
        try {
            return UUID.fromString(mockBulkRunId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid FACEBOOK_MOCK_BULK_RUN_ID", e
            );
        }
    }

    private UUID targetMockJobId() {
        if (!isMock() || bulkEnabled) {
            return null;
        }
        if (mockJobId == null || mockJobId.isBlank()) {
            throw new IllegalStateException(
                    "Single MOCK requires FACEBOOK_MOCK_JOB_ID"
            );
        }
        try {
            return UUID.fromString(mockJobId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid FACEBOOK_MOCK_JOB_ID", e
            );
        }
    }

    private boolean isMock() {
        return "MOCK".equalsIgnoreCase(publishingMode);
    }

    private UUID configuredLiveJobId() {
        if (!isLive() || isBulkLive() || multiJobEnabled) {
            return null;
        }

        if (liveJobId == null || liveJobId.isBlank()) {
            throw new IllegalStateException(
                    "Single LIVE mode requires app.facebook.live-job-id"
            );
        }

        return UUID.fromString(liveJobId.trim());
    }

    private void validateConfiguration() {
        validateConfiguration(false);
    }

    private void validateConfiguration(boolean exactQueuedJob) {

        if (!isMock() && !isLive()) {
            throw new IllegalStateException(
                    "Unsupported Facebook publishing mode"
            );
        }

        if (isMock()) {

            // Fail closed before selecting jobs in either MOCK mode.
            if (bulkEnabled) {
                targetMockBulkRunId();
            } else {
                targetMockJobId();
            }

            if (!MOCK_URL.equals(metaBaseUrl)) {
                throw new IllegalStateException(
                        "MOCK mode requires localhost mock server"
                );
            }

        } else {

            if (!LIVE_URL.equals(metaBaseUrl)) {
                throw new IllegalStateException(
                        "LIVE mode requires https://graph.facebook.com"
                );
            }

            if (!isBulkLive() && !exactQueuedJob) {
                configuredLiveJobId();
            }
        }

        if (!graphVersion.matches("v[0-9]+\\.[0-9]+")) {
            throw new IllegalStateException(
                    "Invalid Meta Graph API version"
            );
        }

        if (isLive() && multiJobEnabled && !isBulkLive()) {
            multiJobStartTime();
        }

        if (maxImagesPerPost < 1) {
            throw new IllegalStateException(
                    "FACEBOOK_MAX_IMAGES_PER_POST must be positive"
            );
        }

        if (batchSize < 1) {
            throw new IllegalStateException(
                    "Publisher batch size must be positive"
            );
        }
    }

    private OffsetDateTime multiJobStartTime() {
        if (multiJobNotBefore == null || multiJobNotBefore.isBlank()) {
            throw new IllegalStateException(
                    "FACEBOOK_MULTI_JOB_NOT_BEFORE is required for Multi-Job LIVE"
            );
        }

        try {
            return OffsetDateTime.parse(multiJobNotBefore.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException(
                    "Invalid FACEBOOK_MULTI_JOB_NOT_BEFORE: "
                            + multiJobNotBefore,
                    ex
            );
        }
    }

    private RestClient createClient() {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);

        factory.setReadTimeout(Duration.ofSeconds(60));

        return RestClient.builder()
                .baseUrl(metaBaseUrl)
                .requestFactory(factory)
                .build();
    }

    public void processBatch() {

        log.info(
                "[FANPAGE WORKER] mode={} | bulk={} | multiJob={} | liveJobId={} | batchSize={}",
                publishingMode,
                bulkEnabled,
                multiJobEnabled,
                liveJobId,
                batchSize

        );

        validateConfiguration();
        reconcileAbandonedJobs();

        int limit = isBulkProcessing()
                ? Math.min(batchSize, 5)
                : isLive() && !multiJobEnabled
                ? 1
                : Math.min(batchSize, 5);

        // Single MOCK must never process more than the explicitly selected job.
        if (isMock() && !bulkEnabled) {
            limit = 1;
        }

        for (int i = 0; i < limit; i++) {

            ClaimedJob job = isBulkProcessing()
                    ? claimBulk()
                    : claim();

            if (job == null) {
                log.warn("[FANPAGE WORKER] No eligible job found");
                return;
            }

            log.info("[FANPAGE WORKER] Claimed job={}", job.id());

            publish(job);
        }
    }


    private ClaimedJob claim() {

        UUID targetJobId = isMock()
                ? targetMockJobId()
                : configuredLiveJobId();
        boolean automaticLive = isLive() && multiJobEnabled;
        OffsetDateTime autoNotBefore = automaticLive ? multiJobStartTime() : null;
        if (isBulkProcessing()) {
            throw new IllegalStateException(
                    "Bulk publishing must use claimBulk(), not claim()"
            );
        }

        return transactions.execute(status -> {

            List<ClaimedJob> claimed = db.query("""
                            WITH picked AS (
                                SELECT j.id
                                FROM publishing_jobs j
                                JOIN social_accounts a
                                    ON a.id = j.social_account_id
                                JOIN posts p
                                    ON p.id = j.post_id
                            
                                WHERE (
                                    (CAST(? AS BOOLEAN) = TRUE
                                        AND j.status = 'PENDING'
                                        AND j.external_post_id IS NULL)
                                    OR
                                    (CAST(? AS BOOLEAN) = FALSE
                                        AND j.status IN ('PENDING', 'FAILED'))
                                )
                                  AND a.platform = 'FACEBOOK_PAGE'
                                  AND a.status = 'CONNECTED'
                                  AND p.status IN ('READY', 'SCHEDULED')
                                  AND p.approval_status = 'APPROVED'
                                  AND (
                                      (j.attempt_count = 0
                                          AND j.external_post_id IS NULL
                                          AND j.published_at IS NULL
                                          AND j.published_url IS NULL)
                                      OR (
                                          -- Exactly one explicitly enabled retry for the known
                                          -- pre-network token guard failure, in single MOCK only.
                                          CAST(? AS BOOLEAN) = TRUE
                                          AND j.status = 'FAILED'
                                          AND j.attempt_count = 1
                                          AND j.last_error = 'MOCK TEST BLOCKED: Expected mock token'
                                          AND j.external_post_id IS NULL
                                          AND j.published_at IS NULL
                                          AND j.published_url IS NULL
                                          AND EXISTS (
                                              SELECT 1 FROM publishing_attempts pa
                                              WHERE pa.publishing_job_id = j.id
                                                AND pa.attempt_number = 1
                                                AND pa.status = 'FAILED'
                                                AND pa.error_message = 'MOCK TEST BLOCKED: Expected mock token'
                                          )
                                          AND NOT EXISTS (
                                              SELECT 1 FROM facebook_photo_upload_progress pu
                                              WHERE pu.publishing_job_id = j.id
                                          )
                                          AND NOT EXISTS (
                                              SELECT 1 FROM facebook_feed_publish_progress fp
                                              WHERE fp.publishing_job_id = j.id
                                          )
                                      )
                                      OR (
                                          j.status = 'PENDING'
                                          AND j.attempt_count BETWEEN 1 AND 2
                                          AND j.external_post_id IS NULL
                                          AND j.published_at IS NULL
                                          AND j.published_url IS NULL
                                          AND EXISTS (
                                              SELECT 1 FROM publishing_reviews r
                                              WHERE r.publishing_job_id = j.id
                                                AND r.attempt_number = j.attempt_count
                                                AND r.decision = 'CONFIRM_NOT_PUBLISHED'
                                                AND NULLIF(BTRIM(r.evidence), '') IS NOT NULL
                                                AND NULLIF(BTRIM(r.note), '') IS NOT NULL
                                          )
                                      )
                                  )
                            
                                  -- Không lấy job thuộc Facebook Bulk Run.
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM facebook_bulk_run_jobs bj
                                      WHERE bj.publishing_job_id = j.id
                                  )
                            
                                  -- Giữ nguyên logic Campaign.
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
                            
                                  AND COALESCE(
                                      j.scheduled_at,
                                      j.created_at
                                  ) <= NOW()
                            
                                  -- Prevent an initial automatic publish when the
                                  -- scheduled time is more than five minutes overdue.
                                  -- A manually reviewed retry (attempt_count > 0)
                                  -- remains governed by the existing review checks.
                                  AND (
                                      j.attempt_count > 0
                                      OR COALESCE(
                                          j.scheduled_at,
                                          j.created_at
                                      ) >= NOW() - INTERVAL '5 minutes'
                                  )
                            
                                  AND (
                                      j.next_retry_at IS NULL
                                      OR j.next_retry_at <= NOW()
                                  )
                            
                                  -- AUTO LIVE: only jobs created after the configured safety cutoff.
                                  AND (
                                      CAST(? AS BOOLEAN) = FALSE
                                      OR j.created_at >= CAST(? AS TIMESTAMPTZ)
                                      -- An older job can only pass via a reviewed retry above.
                                      OR j.attempt_count > 0
                                  )
                            
                                  AND j.attempt_count < ?
                            
                                  -- LIVE: chỉ lấy đúng job được chỉ định.
                                  -- MOCK: lấy job theo queue.
                                  AND (
                                      CAST(? AS UUID) IS NULL
                                      OR j.id = CAST(? AS UUID)
                                  )
                            
                                ORDER BY
                                    COALESCE(
                                        j.next_retry_at,
                                        j.scheduled_at,
                                        j.created_at
                                    ),
                                    j.created_at
                            
                                FOR UPDATE OF j SKIP LOCKED
                                LIMIT 1
                            )
                            
                            UPDATE publishing_jobs j
                            SET
                                status = 'PROCESSING',
                                attempt_count = j.attempt_count + 1,
                                processing_started_at = NOW(),
                                locked_until = NOW() + INTERVAL '10 minutes',
                                next_retry_at = NULL
                            FROM picked
                            WHERE j.id = picked.id
                            
                            RETURNING j.id, j.attempt_count
                            """,
                    (rs, rowNum) -> new ClaimedJob(
                            rs.getObject("id", UUID.class),
                            rs.getInt("attempt_count")
                    ),
                    automaticLive,
                    automaticLive,
                    isMock() && !bulkEnabled && mockPreflightRetryEnabled,
                    automaticLive,
                    autoNotBefore,
                    MAX_ATTEMPTS,
                    targetJobId,
                    targetJobId
            );

            if (claimed.isEmpty()) {
                return null;
            }

            ClaimedJob job = claimed.getFirst();

            db.update("""
                            INSERT INTO publishing_attempts (
                                publishing_job_id,
                                attempt_number,
                                status
                            )
                            VALUES (?, ?, 'PROCESSING')
                            """,
                    job.id(),
                    job.attempt()
            );

            return job;
        });
    }


    private ClaimedJob claimBulk() {

        if (!isBulkProcessing()) {
            throw new IllegalStateException(
                    "Bulk claim requires enabled MOCK or LIVE bulk mode"
            );
        }

        // LIVE bulk remains unchanged; MOCK bulk is limited to one configured run.
        UUID targetRunId = targetMockBulkRunId();

        return transactions.execute(status -> {

            List<ClaimedJob> claimed = db.query("""
                            WITH picked AS (
                                SELECT j.id
                                FROM publishing_jobs j
                            
                                JOIN facebook_bulk_run_jobs bj
                                    ON bj.publishing_job_id = j.id
                            
                                JOIN facebook_bulk_runs br
                                    ON br.id = bj.bulk_run_id
                            
                                JOIN social_accounts a
                                    ON a.id = j.social_account_id
                            
                                JOIN posts p
                                    ON p.id = j.post_id
                            
                                WHERE br.status = 'RUNNING'
                                  AND (CAST(? AS UUID) IS NULL OR br.id = CAST(? AS UUID))
                            
                                  AND br.workspace_id = p.workspace_id
                                  AND br.workspace_id = a.workspace_id
                            
                                  AND j.status = 'PENDING'
                                  AND j.attempt_count = 0
                                  AND j.external_post_id IS NULL
                                  AND j.published_at IS NULL
                                  AND j.published_url IS NULL
                            
                                  AND a.platform = 'FACEBOOK_PAGE'
                                  AND a.status = 'CONNECTED'
                            
                                  AND p.status IN ('READY', 'SCHEDULED')
                                  AND p.approval_status = 'APPROVED'
                            
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM campaign_jobs cj
                                      WHERE cj.publishing_job_id = j.id
                                  )
                            
                                  -- Image posts are supported. Reject incomplete or
                                  -- unsupported media before claiming the job.
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
                            
                                  AND COALESCE(
                                      j.scheduled_at,
                                      j.created_at
                                  ) <= NOW()
                            
                                  -- Bulk publishing is first-attempt only.
                                  -- Never automatically publish an overdue bulk job.
                                  AND COALESCE(
                                      j.scheduled_at,
                                      j.created_at
                                  ) >= NOW() - INTERVAL '5 minutes'
                            
                                  AND (
                                      j.next_retry_at IS NULL
                                      OR j.next_retry_at <= NOW()
                                  )
                            
                                ORDER BY
                                    COALESCE(
                                        j.scheduled_at,
                                        j.created_at
                                    ),
                                    j.created_at
                            
                                FOR UPDATE OF j SKIP LOCKED
                                LIMIT 1
                            )
                            
                            UPDATE publishing_jobs j
                            
                            SET status = 'PROCESSING',
                                attempt_count = j.attempt_count + 1,
                                processing_started_at = NOW(),
                                locked_until = NOW()
                                    + INTERVAL '10 minutes',
                                next_retry_at = NULL
                            
                            FROM picked
                            
                            WHERE j.id = picked.id
                            
                            RETURNING j.id, j.attempt_count
                            """,
                    (rs, rowNum) -> new ClaimedJob(
                            rs.getObject("id", UUID.class),
                            rs.getInt("attempt_count")
                    ),
                    targetRunId,
                    targetRunId,
                    maxImagesPerPost
            );

            if (claimed.isEmpty()) {
                return null;
            }

            ClaimedJob job = claimed.getFirst();

            db.update("""
                            INSERT INTO publishing_attempts (
                                publishing_job_id,
                                attempt_number,
                                status
                            )
                            VALUES (?, ?, 'PROCESSING')
                            """,
                    job.id(),
                    job.attempt()
            );

            return job;
        });
    }

    // Application-side cap only; Meta may reject larger attached_media requests.
    // Start with 10 in production; raise cautiously for controlled LIVE tests.


    /**
     * Deny an unapproved (or no longer executable) job immediately before
     * any outbound Meta request. All SQL claim paths also require approval.
     * <p>
     * Important: a separate approval-revocation transaction can still race
     * this read. The revocation path must coordinate with in-flight workers
     * for a strict no-publish-after-revoke guarantee.
     */
    private void requireApprovedForPublish(ClaimedJob job) {
        Boolean permitted = db.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM publishing_jobs j
                    JOIN posts p ON p.id = j.post_id
                    JOIN social_accounts a ON a.id = j.social_account_id
                    WHERE j.id = ?
                      AND j.status = 'PROCESSING'
                      AND j.attempt_count = ?
                      AND p.approval_status = 'APPROVED'
                      AND p.status IN ('READY', 'SCHEDULED')
                      AND a.platform = 'FACEBOOK_PAGE'
                      AND a.status = 'CONNECTED'
                      AND a.workspace_id = p.workspace_id
                )
                """, Boolean.class, job.id(), job.attempt());
        if (!Boolean.TRUE.equals(permitted)) {
            throw new IllegalStateException(
                    "Publishing blocked: post is not approved or job is no longer eligible"
            );
        }
    }

    /**
     * Phase 2C exact-job entry point; never scans unrelated publishing_jobs.
     * Scheduler/bulk selectors must NOT call this method. Only a strictly gated
     * queue executor may call it. This path is first-attempt only.
     */
    public void publishQueuedJob(UUID jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("Queue jobId is required");
        }
        if (!isLive() || bulkEnabled || !LIVE_URL.equals(metaBaseUrl)) {
            throw new IllegalStateException("Queued Facebook execution requires single LIVE Graph API mode");
        }
        if (!graphVersion.matches("v[0-9]+\\.[0-9]+")) {
            throw new IllegalStateException("Invalid Graph API version");
        }
        if (maxImagesPerPost < 1) {
            throw new IllegalStateException("Invalid maximum image count");
        }
        ClaimedJob claimed = transactions.execute(status -> {
            List<ClaimedJob> rows = db.query("""
                            UPDATE publishing_jobs j
                            SET status='PROCESSING', attempt_count=j.attempt_count+1,
                                processing_started_at=NOW(), locked_until=NOW()+INTERVAL '10 minutes',
                                next_retry_at=NULL
                            FROM posts p, social_accounts a
                            WHERE j.id=? AND j.post_id=p.id AND j.social_account_id=a.id
                              AND j.status='PENDING' AND j.attempt_count=0
                              AND j.external_post_id IS NULL AND j.published_at IS NULL
                              AND j.published_url IS NULL
                              AND a.platform='FACEBOOK_PAGE' AND a.status='CONNECTED'
                              AND a.workspace_id=p.workspace_id
                              AND NULLIF(BTRIM(a.access_token_encrypted),'') IS NOT NULL
                              AND (a.token_expires_at IS NULL OR a.token_expires_at>NOW())
                              AND p.status IN ('READY','SCHEDULED')
                              AND p.approval_status='APPROVED'
                              AND NULLIF(BTRIM(p.content),'') IS NOT NULL
                              AND COALESCE(j.scheduled_at,j.created_at)<=NOW()
                              AND COALESCE(j.scheduled_at,j.created_at)>=NOW()-INTERVAL '5 minutes'
                              AND NOT EXISTS (
                                SELECT 1 FROM post_media m WHERE m.post_id=p.id
                                AND (m.media_type IS DISTINCT FROM 'IMAGE'
                                  OR NULLIF(BTRIM(m.public_url),'') IS NULL)
                              )
                              AND (SELECT COUNT(*) FROM post_media m WHERE m.post_id=p.id) <= ?
                              AND (
                                NOT EXISTS (SELECT 1 FROM campaign_jobs cj WHERE cj.publishing_job_id=j.id)
                                OR EXISTS (SELECT 1 FROM campaign_jobs cj
                                    JOIN campaigns c ON c.id=cj.campaign_id
                                    WHERE cj.publishing_job_id=j.id AND c.status='RUNNING')
                              )
                            RETURNING j.id,j.attempt_count
                            """, (rs, i) -> new ClaimedJob(
                            rs.getObject("id", UUID.class), rs.getInt("attempt_count")),
                    jobId, maxImagesPerPost);
            if (rows.isEmpty()) return null;
            ClaimedJob item = rows.getFirst();
            db.update("""
                    INSERT INTO publishing_attempts(publishing_job_id,attempt_number,status)
                    VALUES (?,?,'PROCESSING')
                    """, item.id(), item.attempt());
            return item;
        });
        if (claimed == null) {
            throw new IllegalStateException("Queued Facebook job is no longer eligible; nothing was sent");
        }
        log.info("[FACEBOOK QUEUED] Claimed exact job={}", jobId);
        publish(claimed, true);
    }

    private void publish(ClaimedJob job) {
        publish(job, false);
    }

    private void publish(ClaimedJob job, boolean exactQueuedJob) {
        boolean requestMayHaveBeenSent = false;

        try {
            // Defensive check after claiming the job, before processing its payload.
            requireApprovedForPublish(job);
            Map<String, Object> row = db.queryForMap("""
                    SELECT a.external_account_id, a.workspace_id, p.content, j.facebook_connection_id
                    FROM publishing_jobs j
                    JOIN social_accounts a ON a.id = j.social_account_id
                    JOIN posts p ON p.id = j.post_id
                    WHERE j.id = ?
                    """, job.id());

            // Read all media before any request. Deterministic ordering.
            List<Map<String, Object>> mediaRows = db.queryForList("""
                    SELECT m.media_type, m.public_url
                    FROM post_media m
                    JOIN publishing_jobs j ON j.post_id = m.post_id
                    WHERE j.id = ?
                    ORDER BY m.display_order ASC, m.id ASC
                    """, job.id());

            if (mediaRows.size() > maxImagesPerPost) {
                throw new IllegalStateException(
                        "Image count " + mediaRows.size()
                                + " exceeds configured publisher limit " + maxImagesPerPost
                );
            }
            if (mediaRows.size() > 10) {
                log.info(
                        "[FANPAGE WORKER] job={} | imageCount={} | configuredMaxImages={}",
                        job.id(),
                        mediaRows.size(),
                        maxImagesPerPost
                );
            }

            List<String> imageUrls = new java.util.ArrayList<>();
            for (Map<String, Object> media : mediaRows) {
                if (!"IMAGE".equals(media.get("media_type"))) {
                    throw new IllegalStateException("Only IMAGE media is supported");
                }
                String url = (String) media.get("public_url");
                validatePublicImageUrl(url);
                imageUrls.add(url);
            }
            // MOCK photo publishing is permitted only against the fixed localhost
            // mock endpoint validated by validateConfiguration().

            String pageId = (String) row.get("external_account_id");
            if (pageId == null || !pageId.matches("[0-9]+")) {
                throw new IllegalStateException("Invalid Facebook Page ID");
            }
            UUID workspaceId = (UUID) row.get("workspace_id");
            if (workspaceId == null) {
                throw new IllegalStateException("Facebook workspace ID is missing");
            }
            String content = (String) row.get("content");
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("Post content must not be empty");
            }

            String token;
            if (isMock()) {
                token = System.getenv("META_PAGE_TOKEN_" + pageId);
                if (!"mock_test_token".equals(token)) {
                    throw new IllegalStateException("MOCK TEST BLOCKED: Expected mock token");
                }
            } else if (isLive()) {
                UUID boundConnectionId = (UUID) row.get("facebook_connection_id");
                if (boundConnectionId != null) {
                    token = tokenStorage.getPageAccessToken(workspaceId, pageId, boundConnectionId);
                    log.info("[FANPAGE WORKER] job={} | using bound Facebook connection id={}",
                            job.id(), boundConnectionId);
                } else {
                    boolean pageConfigured = liveConnectionPageId != null
                            && !liveConnectionPageId.isBlank();
                    boolean userConfigured = liveConnectionUserId != null
                            && !liveConnectionUserId.isBlank();
                    if (pageConfigured != userConfigured) {
                        throw new IllegalStateException(
                                "FACEBOOK_LIVE_CONNECTION_PAGE_ID and FACEBOOK_LIVE_CONNECTION_USER_ID must be set together");
                    }
                    if (pageConfigured && pageId.equals(liveConnectionPageId.trim())) {
                        token = tokenStorage.getPageAccessToken(
                                workspaceId, pageId, liveConnectionUserId.trim());
                        log.info("[FANPAGE WORKER] job={} | explicit Facebook connection selected for Page={}",
                                job.id(), pageId);
                    } else {
                        // Preserve the known-good LIVE path for all other Pages and for an unset selector.
                        token = tokenStorage.getPageAccessToken(workspaceId, pageId);
                    }
                } // Legacy job with no binding: preserve existing env/legacy selection.
            } else {
                throw new IllegalStateException("Unsupported Facebook publishing mode");
            }
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Facebook Page access token is missing");
            }

            validateConfiguration(exactQueuedJob);
            String endpoint;
            String requestBody;
            String accessToken = "&access_token=" + formEncode(token);
            RestClient client = createClient();

            if (imageUrls.isEmpty()) {
                endpoint = "/feed";
                requestBody = "message=" + formEncode(content) + accessToken;
            } else if (imageUrls.size() == 1) {
                // Preserve the previously LIVE-tested single-image behavior.
                endpoint = "/photos";
                requestBody = "caption=" + formEncode(content)
                        + "&url=" + formEncode(imageUrls.getFirst())
                        + "&published=true" + accessToken;
            } else {
                // Upload as unpublished Page photos. Only the final /feed request
                // publishes the multi-photo post to the Page timeline.
                List<String> photoIds = new java.util.ArrayList<>();
                for (int imageIndex = 0; imageIndex < imageUrls.size(); imageIndex++) {
                    String url = imageUrls.get(imageIndex);
                    // Persist intent before the network request; never assume an
                    // interrupted request failed or can safely be repeated.
                    recordUploadStarted(job, imageIndex + 1, url);
                    extendLease(job);
                    log.info("[FANPAGE WORKER] job={} uploading image {}/{}",
                            job.id(), imageIndex + 1, imageUrls.size());
                    String uploadBody = "url=" + formEncode(url)
                            + "&published=false" + accessToken;
                    // Any network attempt may have reached Meta. Never blindly retry.
                    requireApprovedForPublish(job);
                    requestMayHaveBeenSent = true;
                    JsonNode uploaded = sendMetaPost(client, pageId, "/photos", uploadBody);
                    String photoId = uploaded.path("id").asText();
                    if (photoId.isBlank()) {
                        throw new IllegalStateException("Meta photo upload returned no photo id");
                    }
                    recordUploadSucceeded(job, imageIndex + 1, photoId);
                    photoIds.add(photoId);
                    log.info("[FANPAGE WORKER] job={} uploaded image {}/{}",
                            job.id(), imageIndex + 1, imageUrls.size());
                }

                endpoint = "/feed";
                StringBuilder feedBody = new StringBuilder("message=")
                        .append(formEncode(content));
                for (int i = 0; i < photoIds.size(); i++) {
                    String attached = json.writeValueAsString(Map.of("media_fbid", photoIds.get(i)));
                    feedBody.append("&attached_media[").append(i).append("]=")
                            .append(formEncode(attached));
                }
                requestBody = feedBody.append(accessToken).toString();
            }

            if (imageUrls.size() > 1) {
                recordFeedStarted(job);
                extendLease(job);
            }
            // Final preflight before the public publishing request.
            requireApprovedForPublish(job);
            requestMayHaveBeenSent = true;
            JsonNode result = sendMetaPost(client, pageId, endpoint, requestBody);
            String photoId = result.path("id").asText();
            String postId = result.path("post_id").asText();
            String externalId = imageUrls.size() == 1
                    ? (postId.isBlank() ? photoId : postId)
                    : photoId;
            if (externalId.isBlank()) {
                throw new IllegalStateException("Meta response did not include a publication id");
            }
            String publishedUrl = imageUrls.size() == 1 && postId.isBlank()
                    ? "https://www.facebook.com/photo.php?fbid=" + photoId
                    : "https://www.facebook.com/" + externalId;
            if (imageUrls.size() > 1) {
                recordFeedSucceeded(job, externalId);
            }
            markPublished(job, externalId, publishedUrl);
            log.info("[FANPAGE WORKER] Published job={} | images={}", job.id(), imageUrls.size());
        } catch (Exception e) {
            // Do not persist raw Meta responses: they may contain sensitive data.
            String message = safeError(e, requestMayHaveBeenSent);
            if (requestMayHaveBeenSent) {
                markNeedsReview(job, UNKNOWN_OUTCOME + " Cause: " + message);
                log.warn("[FANPAGE WORKER] Job={} NEEDS_REVIEW", job.id());
            } else {
                markFailed(job, message);
                log.warn("[FANPAGE WORKER] Job={} FAILED: {}", job.id(), message);
            }
        }
    }

    // Reconcile only after an additional grace period. Never automatically retry LIVE.
    // A stale worker must not be allowed to overwrite the reconciled state.
    private void reconcileAbandonedJobs() {
        if (!isLive()) return;
        transactions.executeWithoutResult(tx -> {
            List<Map<String, Object>> abandoned = db.queryForList("""
                    SELECT id, attempt_count FROM publishing_jobs
                    WHERE status = 'PROCESSING'
                      AND locked_until < NOW() - INTERVAL '5 minutes'
                      AND external_post_id IS NULL
                    FOR UPDATE SKIP LOCKED
                    """);
            for (Map<String, Object> row : abandoned) {
                UUID id = (UUID) row.get("id");
                int attempt = ((Number) row.get("attempt_count")).intValue();
                int changed = db.update("""
                        UPDATE publishing_jobs SET status='NEEDS_REVIEW',
                            last_error='Lease expired; Meta outcome requires manual review',
                            locked_until=NULL, next_retry_at=NULL
                        WHERE id=? AND status='PROCESSING' AND attempt_count=?
                        """, id, attempt);
                if (changed == 1) {
                    db.update("""
                            UPDATE publishing_attempts SET status='UNKNOWN',
                                error_message='Worker lease expired; outcome unknown',
                                finished_at=NOW()
                            WHERE publishing_job_id=? AND attempt_number=?
                              AND status='PROCESSING'
                            """, id, attempt);
                    log.warn("[FANPAGE WORKER] Abandoned job={} moved to NEEDS_REVIEW", id);
                }
            }
        });
    }

    private void extendLease(ClaimedJob job) {
        int updated = db.update("""
                UPDATE publishing_jobs
                SET locked_until = NOW() + INTERVAL '10 minutes'
                WHERE id=? AND attempt_count=? AND status='PROCESSING'
                """, job.id(), job.attempt());
        requireOneUpdate(updated, "publishing_jobs lease");
    }

    private void recordUploadStarted(ClaimedJob job, int position, String url) {
        db.update("""
                INSERT INTO facebook_photo_upload_progress
                    (publishing_job_id, attempt_number, image_position, image_url, status)
                VALUES (?, ?, ?, ?, 'REQUEST_STARTED')
                ON CONFLICT (publishing_job_id, attempt_number, image_position)
                DO NOTHING
                """, job.id(), job.attempt(), position, url);
    }

    private void recordUploadSucceeded(ClaimedJob job, int position, String photoId) {
        int changed = db.update("""
                UPDATE facebook_photo_upload_progress
                SET status='UPLOADED', meta_photo_id=?, updated_at=NOW()
                WHERE publishing_job_id=? AND attempt_number=? AND image_position=?
                  AND status='REQUEST_STARTED'
                """, photoId, job.id(), job.attempt(), position);
        requireOneUpdate(changed, "facebook_photo_upload_progress");
    }

    private void recordFeedStarted(ClaimedJob job) {
        db.update("""
                INSERT INTO facebook_feed_publish_progress
                    (publishing_job_id, attempt_number, status)
                VALUES (?, ?, 'REQUEST_STARTED')
                ON CONFLICT (publishing_job_id, attempt_number) DO NOTHING
                """, job.id(), job.attempt());
    }

    private void recordFeedSucceeded(ClaimedJob job, String postId) {
        int changed = db.update("""
                UPDATE facebook_feed_publish_progress
                SET status='CONFIRMED', meta_post_id=?, updated_at=NOW()
                WHERE publishing_job_id=? AND attempt_number=?
                  AND status='REQUEST_STARTED'
                """, postId, job.id(), job.attempt());
        requireOneUpdate(changed, "facebook_feed_publish_progress");
    }

    private String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JsonNode sendMetaPost(RestClient client, String pageId, String endpoint, String requestBody)
            throws java.io.IOException {
        String body = client.post()
                .uri("/" + graphVersion + "/" + pageId + endpoint)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(requestBody)
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Meta API returned an empty response");
        }
        return json.readTree(body);
    }

    /**
     * Validate media URL before any publishing request is sent.
     */
    private void validatePublicImageUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("IMAGE requires a public HTTPS URL");
        }
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getFragment() != null) {
                throw new IllegalStateException("Invalid public HTTPS image URL");
            }
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal")) {
                throw new IllegalStateException("Private image URL is not supported");
            }
            // Reject literal IP addresses; public DNS names are expected.
            if (host.matches("[0-9.]+") || host.contains(":")) {
                throw new IllegalStateException("Use a public DNS hostname for images");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid image URL");
        }
    }

    private String safeError(
            Exception error,
            boolean requestMayHaveBeenSent
    ) {

        if (requestMayHaveBeenSent) {
            return error.getClass().getSimpleName();
        }

        if (error instanceof IllegalStateException
                || error instanceof IllegalArgumentException) {

            String message = error.getMessage();

            if (message != null && !message.isBlank()) {
                return message.length() > 1500
                        ? message.substring(0, 1500)
                        : message;
            }
        }

        return error.getClass().getSimpleName();
    }

    private void markPublished(
            ClaimedJob job,
            String externalId,
            String publishedUrl
    ) {

        transactions.executeWithoutResult(status -> {

            int updated = db.update("""
                            UPDATE publishing_jobs
                            SET status = 'PUBLISHED',
                                external_post_id = ?,
                                published_url = ?,
                                published_at = NOW(),
                                last_error = NULL,
                                next_retry_at = NULL,
                                locked_until = NULL
                            WHERE id = ?
                              AND status = 'PROCESSING'
                              AND attempt_count = ?
                            """,
                    externalId,
                    publishedUrl,
                    job.id(),
                    job.attempt()
            );

            requireOneUpdate(updated, "publishing_jobs");

            int attemptsUpdated = db.update("""
                            UPDATE publishing_attempts
                            SET status = 'SUCCESS',
                                error_message = NULL,
                                finished_at = NOW()
                            WHERE publishing_job_id = ?
                              AND attempt_number = ?
                              AND status = 'PROCESSING'
                            """,
                    job.id(),
                    job.attempt()
            );

            requireOneUpdate(
                    attemptsUpdated,
                    "publishing_attempts"
            );
            db.update("""
                    UPDATE excel_import_rows e
                    SET processing_status = 'PUBLISHED',
                        published_at = NOW(),
                        published_url = ?
                    FROM publishing_jobs j
                    WHERE j.id = ?
                      AND j.post_id = e.post_id
                      AND e.processing_status IN ('IMPORTED', 'PUBLISHED')
                    """, publishedUrl, job.id());

            // Complete the parent bulk run in the same transaction as the
            // successful publication and Excel synchronization.
            completeBulkRunIfAllPublished(job.id());
        });
    }

    /**
     * Finalize a RUNNING bulk run only when all of its linked jobs are
     * PUBLISHED. Locking the parent row serializes concurrent workers:
     * the last finishing worker checks the committed result of earlier ones.
     * <p>
     * Called only inside the markPublished TransactionTemplate transaction.
     * Non-bulk jobs are unaffected.
     */
    private void completeBulkRunIfAllPublished(UUID publishedJobId) {
        List<UUID> runIds = db.query("""
                        SELECT br.id
                        FROM facebook_bulk_runs br
                        JOIN facebook_bulk_run_jobs bj
                          ON bj.bulk_run_id = br.id
                        WHERE bj.publishing_job_id = ?
                          AND br.status = 'RUNNING'
                        ORDER BY br.id
                        FOR UPDATE OF br
                        """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                publishedJobId
        );

        for (UUID runId : runIds) {
            int completed = db.update("""
                    UPDATE facebook_bulk_runs br
                    SET status = 'COMPLETED',
                        finished_at = NOW()
                    WHERE br.id = ?
                      AND br.status = 'RUNNING'
                      AND EXISTS (
                          SELECT 1
                          FROM facebook_bulk_run_jobs bj
                          WHERE bj.bulk_run_id = br.id
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM facebook_bulk_run_jobs bj
                          JOIN publishing_jobs j
                            ON j.id = bj.publishing_job_id
                          WHERE bj.bulk_run_id = br.id
                            AND j.status IS DISTINCT FROM 'PUBLISHED'
                      )
                    """, runId);

            if (completed == 1) {
                log.info("[FACEBOOK BULK] Completed run={} after published job={}",
                        runId, publishedJobId);
            }
        }
    }

    private void markFailed(
            ClaimedJob job,
            String message
    ) {

        transactions.executeWithoutResult(status -> {

            int updated = db.update("""
                            UPDATE publishing_jobs
                            SET status = 'FAILED',
                                last_error = ?,
                                next_retry_at = CASE
                                    WHEN attempt_count < ?
                                        THEN NOW() + INTERVAL '10 minutes'
                                    ELSE NULL
                                END,
                                locked_until = NULL
                            WHERE id = ?
                              AND status = 'PROCESSING'
                              AND attempt_count = ?
                            """,
                    message,
                    MAX_ATTEMPTS,
                    job.id(),
                    job.attempt()
            );

            requireOneUpdate(updated, "publishing_jobs");

            int attemptsUpdated = db.update("""
                            UPDATE publishing_attempts
                            SET status = 'FAILED',
                                error_message = ?,
                                finished_at = NOW()
                            WHERE publishing_job_id = ?
                              AND attempt_number = ?
                              AND status = 'PROCESSING'
                            """,
                    message,
                    job.id(),
                    job.attempt()
            );

            requireOneUpdate(
                    attemptsUpdated,
                    "publishing_attempts"
            );
        });
    }

    private void markNeedsReview(
            ClaimedJob job,
            String message
    ) {

        transactions.executeWithoutResult(status -> {

            int updated = db.update("""
                            UPDATE publishing_jobs
                            SET status = 'NEEDS_REVIEW',
                                last_error = ?,
                                next_retry_at = NULL,
                                locked_until = NULL
                            WHERE id = ?
                              AND status = 'PROCESSING'
                              AND attempt_count = ?
                            """,
                    message,
                    job.id(),
                    job.attempt()
            );

            requireOneUpdate(updated, "publishing_jobs");

            int attemptsUpdated = db.update("""
                            UPDATE publishing_attempts
                            SET status = 'UNKNOWN',
                                error_message = ?,
                                finished_at = NOW()
                            WHERE publishing_job_id = ?
                              AND attempt_number = ?
                              AND status = 'PROCESSING'
                            """,
                    message,
                    job.id(),
                    job.attempt()
            );

            requireOneUpdate(
                    attemptsUpdated,
                    "publishing_attempts"
            );
        });
    }

    private void requireOneUpdate(
            int updated,
            String table
    ) {

        if (updated != 1) {
            throw new IllegalStateException(
                    "Unexpected update count for "
                            + table
                            + ": "
                            + updated
            );
        }
    }
}
