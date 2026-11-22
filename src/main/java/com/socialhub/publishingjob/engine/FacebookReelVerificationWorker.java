package com.socialhub.publishingjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.facebookoauth.storage.FacebookTokenStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.runtime",
        name = "role",
        havingValue = "api",
        matchIfMissing = true
)
public class FacebookReelVerificationWorker {

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final FacebookTokenStorage tokenStorage;

    @Value("${app.facebook.reels-auto-verify-enabled:false}")
    private boolean enabled;

    @Value("${app.publisher-enabled:false}")
    private boolean publisherEnabled;

    @Value("${FACEBOOK_PUBLISHING_MODE:MOCK}")
    private String publishingMode;

    @Value("${META_BASE_URL:http://127.0.0.1:9090}")
    private String metaBaseUrl;

    @Value("${META_GRAPH_VERSION:v23.0}")
    private String graphVersion;

    private static final String LIVE_URL =
            "https://graph.facebook.com";

    private static final HttpClient HTTP =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    /**
     * Reconcile Facebook Reels after Meta has accepted FINISH.
     * <p>
     * This Worker only reads Meta Graph API.
     * It does not START, UPLOAD, FINISH or retry a Reel.
     */
    @Scheduled(
            fixedDelayString =
                    "${app.facebook.reels-verify-poll-ms:60000}"
    )
    public void reconcile() {

        log.info(
                "[REEL VERIFY] Scheduler tick | enabled={} | publisherEnabled={} | mode={}",
                enabled,
                publisherEnabled,
                publishingMode
        );

        if (!enabled) {
            log.debug("[REEL VERIFY] Disabled by configuration");
            return;
        }

        if (!publisherEnabled) {
            log.debug("[REEL VERIFY] Publisher is disabled");
            return;
        }

        if (!"LIVE".equalsIgnoreCase(publishingMode)) {
            log.debug("[REEL VERIFY] Skipped: not LIVE mode");
            return;
        }

        if (!LIVE_URL.equals(metaBaseUrl)) {
            log.warn("[REEL VERIFY] Invalid LIVE Meta base URL");
            return;
        }

        if (!graphVersion.matches("v[0-9]+\\.[0-9]+")) {
            log.warn("[REEL VERIFY] Invalid Meta Graph version");
            return;
        }

        List<Map<String, Object>> rows;

        try {
            rows = db.queryForList("""
                    SELECT
                        r.publishing_job_id,
                        r.attempt_number,
                        r.meta_video_id,
                        a.external_account_id AS page_id,
                        a.workspace_id,
                        j.facebook_connection_id
                    FROM facebook_reel_publications r
                    JOIN publishing_jobs j
                        ON j.id = r.publishing_job_id
                    JOIN social_accounts a
                        ON a.id = j.social_account_id
                    WHERE r.phase = 'FINISH_ACCEPTED'
                      AND j.status = 'NEEDS_REVIEW'
                      AND j.attempt_count = r.attempt_number
                      AND a.platform = 'FACEBOOK_PAGE'
                      AND a.status = 'CONNECTED'
                      AND (
                          r.last_checked_at IS NULL
                          OR r.last_checked_at <
                             NOW() - INTERVAL '1 minute'
                      )
                    ORDER BY r.updated_at ASC
                    LIMIT 5
                    """);

        } catch (Exception e) {

            log.error(
                    "[REEL VERIFY] Cannot load verification queue | errorType={}",
                    e.getClass().getSimpleName()
            );

            return;
        }

        log.info(
                "[REEL VERIFY] enabled={} | eligibleJobs={}",
                enabled,
                rows.size()
        );

        if (rows.isEmpty()) {
            return;
        }

        for (Map<String, Object> row : rows) {

            try {
                verifyOne(row);

            } catch (Exception e) {

                // Never log tokens or raw Graph API responses.
                log.warn(
                        "[REEL VERIFY] job={} | verification deferred | errorType={}",
                        row.get("publishing_job_id"),
                        e.getClass().getSimpleName()
                );
            }
        }
    }

    /**
     * Verify one Reel by checking Meta publication status.
     */
    private void verifyOne(
            Map<String, Object> row
    ) throws Exception {

        UUID jobId =
                (UUID) row.get("publishing_job_id");

        int attempt =
                ((Number) row.get("attempt_number")).intValue();

        String videoId =
                (String) row.get("meta_video_id");

        String pageId =
                (String) row.get("page_id");

        UUID workspaceId =
                (UUID) row.get("workspace_id");

        UUID connectionId =
                (UUID) row.get("facebook_connection_id");

        log.info(
                "[REEL VERIFY] Checking job={} | attempt={} | videoId={}",
                jobId,
                attempt,
                videoId
        );

        if (videoId == null
                || !videoId.matches("[0-9]+")
                || pageId == null
                || !pageId.matches("[0-9]+")
                || connectionId == null
                || workspaceId == null) {

            log.warn(
                    "[REEL VERIFY] Invalid verification metadata | job={}",
                    jobId
            );

            return;
        }

        String token = tokenStorage.getPageAccessToken(
                workspaceId,
                pageId,
                connectionId
        );

        if (token == null || token.isBlank()) {

            log.warn(
                    "[REEL VERIFY] Page access token unavailable | job={}",
                    jobId
            );

            return;
        }

        // Read publication status from Meta Graph API.
        JsonNode result = graphGet(
                videoId,
                "status",
                token
        );

        JsonNode status = result.path("status");

        String publishStatus = status
                .path("publishing_phase")
                .path("publish_status")
                .asText("");

        String phaseStatus = status
                .path("publishing_phase")
                .path("status")
                .asText("");

        String videoStatus = status
                .path("video_status")
                .asText("");

        log.info(
                "[REEL VERIFY] job={} | publishStatus={} | phaseStatus={} | videoStatus={}",
                jobId,
                publishStatus,
                phaseStatus,
                videoStatus
        );

        String diagnosticStatus =
                publishStatus + "/" + phaseStatus + "/" + videoStatus;

        if (diagnosticStatus.length() > 120) {
            diagnosticStatus =
                    diagnosticStatus.substring(0, 120);
        }

        // Save Meta's latest status.
        db.update("""
                        UPDATE facebook_reel_publications
                        SET last_checked_at = NOW(),
                            last_status = ?,
                            updated_at = NOW()
                        WHERE publishing_job_id = ?
                          AND attempt_number = ?
                          AND phase = 'FINISH_ACCEPTED'
                        """,
                diagnosticStatus,
                jobId,
                attempt
        );

        // Never mark published unless Meta explicitly confirms it.
        if (!"published".equalsIgnoreCase(publishStatus)) {

            log.info(
                    "[REEL VERIFY] job={} not confirmed published yet",
                    jobId
            );

            return;
        }

        /*
         * Meta has confirmed publish_status = published.
         *
         * First attempt to retrieve permalink_url.
         * If it is unavailable, use the canonical Reel URL
         * constructed from the verified numeric video ID.
         *
         * The fallback URL is a display URL, not independent
         * proof of public accessibility.
         */
        String permalink;

        try {

            JsonNode video = graphGet(
                    videoId,
                    "permalink_url",
                    token
            );

            permalink = video
                    .path("permalink_url")
                    .asText("");

        } catch (Exception e) {

            log.info(
                    "[REEL VERIFY] job={} permalink unavailable; using fallback",
                    jobId
            );

            // Do not return. Meta already confirmed publication.
            permalink = "";
        }

        if (!isFacebookPermalink(permalink)) {

            permalink =
                    "https://www.facebook.com/reel/" + videoId;

            log.info(
                    "[REEL VERIFY] job={} Meta confirmed published; using Reel URL fallback",
                    jobId
            );
        }

        // Required because this value is used inside a lambda.
        final String publishedPermalink = permalink;

        /*
         * Atomic database confirmation.
         *
         * The job can transition only once, and only while
         * the current attempt is still awaiting review.
         */
        transactions.executeWithoutResult(tx -> {

            int changed = db.update("""
                            UPDATE publishing_jobs j
                            SET status = 'PUBLISHED',
                                external_post_id = ?,
                                published_url = ?,
                                published_at = NOW(),
                                last_error = NULL,
                                locked_until = NULL,
                                next_retry_at = NULL
                            WHERE j.id = ?
                              AND j.status = 'NEEDS_REVIEW'
                              AND j.attempt_count = ?
                              AND j.external_post_id IS NULL
                              AND j.published_at IS NULL
                              AND j.published_url IS NULL
                              AND EXISTS (
                                  SELECT 1
                                  FROM facebook_reel_publications r
                                  WHERE r.publishing_job_id = j.id
                                    AND r.attempt_number = ?
                                    AND r.meta_video_id = ?
                                    AND r.phase = 'FINISH_ACCEPTED'
                              )
                            """,
                    videoId,
                    publishedPermalink,
                    jobId,
                    attempt,
                    attempt,
                    videoId
            );

            if (changed != 1) {

                log.info(
                        "[REEL VERIFY] job={} already handled or no longer eligible",
                        jobId
                );

                return;
            }

            // Mark the existing publishing attempt as successful.
            db.update("""
                            UPDATE publishing_attempts
                            SET status = 'SUCCESS',
                                error_message = NULL
                            WHERE publishing_job_id = ?
                              AND attempt_number = ?
                              AND status = 'UNKNOWN'
                            """,
                    jobId,
                    attempt
            );

            // Record that Meta publication has been verified.
            db.update("""
                            UPDATE facebook_reel_publications
                            SET phase = 'VERIFIED_PUBLISHED',
                                updated_at = NOW()
                            WHERE publishing_job_id = ?
                              AND attempt_number = ?
                              AND phase = 'FINISH_ACCEPTED'
                            """,
                    jobId,
                    attempt
            );

            // Synchronize Excel import rows related to this post.
            db.update("""
                            UPDATE excel_import_rows e
                            SET processing_status = 'PUBLISHED',
                                published_at = j.published_at,
                                published_url = j.published_url
                            FROM publishing_jobs j
                            WHERE j.id = ?
                              AND e.post_id = j.post_id
                              AND e.processing_status IN (
                                  'IMPORTED',
                                  'AUTO_SCHEDULED'
                              )
                            """,
                    jobId
            );

            log.info(
                    "[REEL VERIFY] job={} auto-confirmed PUBLISHED | videoId={}",
                    jobId,
                    videoId
            );
        });
    }

    /**
     * Validate a Meta permalink.
     */
    private boolean isFacebookPermalink(String permalink) {

        if (permalink == null || permalink.isBlank()) {
            return false;
        }

        try {

            URI uri = URI.create(permalink);

            String host = uri.getHost();

            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (
                    "facebook.com".equalsIgnoreCase(host)
                            || host.toLowerCase(Locale.ROOT)
                            .endsWith(".facebook.com")
            )
                    && uri.getUserInfo() == null;

        } catch (IllegalArgumentException e) {

            return false;
        }
    }

    /**
     * Read-only Graph API request.
     * <p>
     * The Page token is sent in the Authorization header,
     * never as a query parameter.
     */
    private JsonNode graphGet(
            String videoId,
            String fields,
            String token
    ) throws Exception {

        String url =
                LIVE_URL + "/"
                        + graphVersion + "/"
                        + videoId
                        + "?fields="
                        + URLEncoder.encode(
                        fields,
                        StandardCharsets.UTF_8
                );

        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(url)
                        )
                        .timeout(Duration.ofSeconds(20))
                        .header(
                                "Authorization",
                                "Bearer " + token
                        )
                        .GET()
                        .build();

        HttpResponse<String> response =
                HTTP.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() != 200) {

            throw new IllegalStateException(
                    "Meta status HTTP "
                            + response.statusCode()
            );
        }

        return json.readTree(response.body());
    }
}