package com.socialhub.publishingjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.facebookoauth.storage.FacebookTokenStorage;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only reconciliation for Page VIDEO jobs after /{pageId}/videos accepted an upload.
 * No upload, publish, retry, or mutation of a live Meta post occurs in this worker.
 *
 * A video being encoded/ready is NOT sufficient proof it is published. We require
 * Meta to explicitly report publication, or a published Page post pointing to the
 * exact uploaded video ID. Unexpected or unavailable responses leave NEEDS_REVIEW.
 *
 * Its network calls run on a dedicated single thread so the normal
 * Facebook/Threads publisher scheduler is not blocked.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.runtime", name = "role", havingValue = "api", matchIfMissing = true)
public class FacebookVideoVerificationWorker {
    private static final String LIVE_GRAPH = "https://graph.facebook.com";
    private static final Pattern UPLOAD_RECEIPT = Pattern.compile(
            "^Video uploaded to Meta \\(id=([0-9]+)\\);.*$", Pattern.DOTALL);
    private static final long MIN_RECHECK_NS = Duration.ofMinutes(2).toNanos();
    private static final int MAX_PER_SCAN = 8;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final FacebookTokenStorage tokenStorage;

    @Value("${app.facebook.video-auto-verify-enabled:false}")
    private boolean enabled;
    @Value("${app.publisher-enabled:false}")
    private boolean publisherEnabled;
    @Value("${FACEBOOK_PUBLISHING_MODE:MOCK}")
    private String publishingMode;
    @Value("${META_BASE_URL:http://127.0.0.1:9090}")
    private String metaBaseUrl;
    @Value("${META_GRAPH_VERSION:v23.0}")
    private String graphVersion;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<UUID, Long> checkedAt = new ConcurrentHashMap<>();
    private final ExecutorService verifier = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "facebook-video-verifier");
        thread.setDaemon(true);
        return thread;
    });

    @Scheduled(fixedDelayString = "${app.facebook.video-verify-poll-ms:60000}")
    public void poll() {
        if (!enabled || !publisherEnabled || !"LIVE".equalsIgnoreCase(publishingMode)) {
            return;
        }
        if (!LIVE_GRAPH.equals(metaBaseUrl) || !graphVersion.matches("v[0-9]+\\.[0-9]+")) {
            log.warn("[VIDEO VERIFY] Skipped: invalid LIVE Meta configuration");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            verifier.execute(() -> {
                try {
                    reconcileBatch();
                } catch (Exception e) {
                    log.warn("[VIDEO VERIFY] Batch deferred | errorType={}",
                            e.getClass().getSimpleName());
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            log.warn("[VIDEO VERIFY] Could not dispatch verification | errorType={}",
                    e.getClass().getSimpleName());
        }
    }

    private void reconcileBatch() {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT j.id AS job_id, j.attempt_count, j.last_error,
                       j.facebook_connection_id,
                       a.external_account_id AS page_id, a.workspace_id
                FROM publishing_jobs j
                JOIN social_accounts a ON a.id = j.social_account_id
                WHERE j.status = 'NEEDS_REVIEW'
                  AND j.last_error LIKE 'Video uploaded to Meta (id=%'
                  AND j.external_post_id IS NULL
                  AND j.published_at IS NULL
                  AND j.published_url IS NULL
                  AND a.platform = 'FACEBOOK_PAGE'
                  AND a.status = 'CONNECTED'
                  AND EXISTS (
                    SELECT 1 FROM post_media m
                    WHERE m.post_id = j.post_id AND m.media_type = 'VIDEO'
                  )
                ORDER BY j.scheduled_at ASC, j.id ASC
                LIMIT 2000
                """);
        int checked = 0;
        for (Map<String, Object> row : rows) {
            UUID jobId = (UUID) row.get("job_id");
            long now = System.nanoTime();
            Long last = checkedAt.get(jobId);
            if (last != null && now - last < MIN_RECHECK_NS) {
                continue;
            }
            checkedAt.put(jobId, now);
            try {
                verifyOne(row);
            } catch (Exception e) {
                // Do not expose Page access tokens or raw Meta response bodies.
                log.warn("[VIDEO VERIFY] job={} deferred | errorType={}",
                        jobId, e.getClass().getSimpleName());
            }
            if (++checked >= MAX_PER_SCAN) {
                break;
            }
        }
        if (checked > 0) {
            log.info("[VIDEO VERIFY] examined={} eligible={}", checked, rows.size());
        }
    }

    private void verifyOne(Map<String, Object> row) throws Exception {
        UUID jobId = (UUID) row.get("job_id");
        int attempt = ((Number) row.get("attempt_count")).intValue();
        String receipt = (String) row.get("last_error");
        UUID workspaceId = (UUID) row.get("workspace_id");
        UUID connectionId = (UUID) row.get("facebook_connection_id");
        String pageId = (String) row.get("page_id");
        Matcher matcher = UPLOAD_RECEIPT.matcher(receipt == null ? "" : receipt);
        if (!matcher.matches() || pageId == null || !pageId.matches("[0-9]+")
                || workspaceId == null || connectionId == null) {
            log.warn("[VIDEO VERIFY] job={} invalid or missing verification metadata", jobId);
            return;
        }
        String videoId = matcher.group(1);
        String token = tokenStorage.getPageAccessToken(workspaceId, pageId, connectionId);
        if (token == null || token.isBlank()) {
            log.warn("[VIDEO VERIFY] job={} Page token not available", jobId);
            return;
        }

        // Video processing can finish before publishing. Do not treat `ready`
        // or HTTP 200 alone as proof a public post exists.
        JsonNode meta = graphGet(videoId, "status", token);
        JsonNode status = meta.path("status");
        String videoStatus = status.path("video_status").asText("");
        String publishStatus = status.path("publishing_phase")
                .path("publish_status").asText("");
        if ("error".equalsIgnoreCase(videoStatus)
                || "error".equalsIgnoreCase(publishStatus)) {
            log.warn("[VIDEO VERIFY] job={} Meta reports error; leave for review", jobId);
            return;
        }

        String permalink = "";
        boolean published = "published".equalsIgnoreCase(publishStatus);
        if (published) {
            // Separate fields call: a missing permalink field must not invalidate
            // an explicit Meta publication confirmation.
            try {
                permalink = graphGet(videoId, "permalink_url", token)
                        .path("permalink_url").asText("");
            } catch (Exception ignored) {
                // Use canonical URL only after Meta independently confirmed publication.
            }
        } else if ("ready".equalsIgnoreCase(videoStatus)) {
            // Some VIDEO responses expose readiness without publishing_phase.
            // Read an explicit publication flag if this field is available.
            try {
                JsonNode video = graphGet(videoId,
                        "is_published,permalink_url", token);
                if (video.path("is_published").asBoolean(false)) {
                    published = true;
                    permalink = video.path("permalink_url").asText("");
                }
            } catch (Exception ignored) {
                // The current Graph version may not expose these fields.
            }
            // Otherwise require an explicitly published Page feed post
            // tied to the exact video ID, never just an encoded video.
            if (!published) {
                PagePostProof pagePost = findPublishedPagePost(pageId, videoId, token);
                if (pagePost != null) {
                    published = true;
                    permalink = pagePost.permalink();
                }
            }
        }
        if (!published) {
            log.info("[VIDEO VERIFY] job={} still unconfirmed | videoStatus={} publishStatus={}",
                    jobId, videoStatus, publishStatus);
            return;
        }
        if (!isFacebookPermalink(permalink)) {
            // A display fallback, not independent verification evidence.
            permalink = "https://www.facebook.com/watch/?v=" + videoId;
        }
        String confirmedPermalink = permalink;
        transactions.executeWithoutResult(tx -> {
            int updated = db.update("""
                    UPDATE publishing_jobs j
                    SET status = 'PUBLISHED',
                        external_post_id = ?, published_url = ?,
                        published_at = NOW(), last_error = NULL,
                        next_retry_at = NULL, locked_until = NULL
                    WHERE j.id = ? AND j.status = 'NEEDS_REVIEW'
                      AND j.attempt_count = ?
                      AND j.last_error = ?
                      AND j.external_post_id IS NULL
                      AND j.published_url IS NULL
                      AND j.published_at IS NULL
                      AND EXISTS (
                        SELECT 1 FROM post_media m
                        WHERE m.post_id = j.post_id AND m.media_type = 'VIDEO'
                      )
                    """, videoId, confirmedPermalink, jobId, attempt, receipt);
            if (updated != 1) {
                log.info("[VIDEO VERIFY] job={} no longer eligible", jobId);
                return;
            }
            db.update("""
                    UPDATE publishing_attempts
                    SET status = 'SUCCESS', error_message = NULL
                    WHERE publishing_job_id = ? AND attempt_number = ?
                      AND status = 'UNKNOWN'
                    """, jobId, attempt);
            db.update("""
                    UPDATE excel_import_rows e
                    SET processing_status = 'PUBLISHED',
                        published_at = j.published_at,
                        published_url = j.published_url
                    FROM publishing_jobs j
                    WHERE j.id = ? AND e.post_id = j.post_id
                      AND e.processing_status IN ('IMPORTED', 'AUTO_SCHEDULED')
                    """, jobId);
            log.info("[VIDEO VERIFY] job={} auto-confirmed PUBLISHED videoId={}", jobId, videoId);
        });
    }

    private record PagePostProof(String permalink) { }

    private PagePostProof findPublishedPagePost(String pageId, String videoId,
                                               String token) {
        try {
            // The feed is read-only. Never publish or retry here.
            JsonNode feed = graphGet(pageId + "/feed",
                    "id,object_id,is_published,permalink_url", token);
            for (JsonNode post : feed.path("data")) {
                if (videoId.equals(post.path("object_id").asText(""))
                        && post.path("is_published").asBoolean(false)) {
                    return new PagePostProof(post.path("permalink_url").asText(""));
                }
            }
        } catch (Exception ignored) {
            // Missing permission, unsupported field, or transient Meta failure:
            // leave the job unconfirmed rather than guessing.
        }
        return null;
    }

    private boolean isFacebookPermalink(String link) {
        try {
            URI url = URI.create(link == null ? "" : link);
            String host = url.getHost();
            return "https".equalsIgnoreCase(url.getScheme())
                    && url.getUserInfo() == null && host != null
                    && ("facebook.com".equalsIgnoreCase(host)
                    || host.toLowerCase(Locale.ROOT).endsWith(".facebook.com")
                    || "fb.watch".equalsIgnoreCase(host));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private JsonNode graphGet(String path, String fields, String token) throws Exception {
        // Both path segments are generated from numeric IDs and a fixed API suffix.
        String url = LIVE_GRAPH + "/" + graphVersion + "/" + path
                + "?fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Meta read HTTP " + response.statusCode());
        }
        return json.readTree(response.body());
    }

    @PreDestroy
    public void shutdown() {
        verifier.shutdown();
    }
}
