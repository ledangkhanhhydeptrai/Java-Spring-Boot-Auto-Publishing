package com.socialhub.threads;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import com.socialhub.socialaccount.entity.SocialAccount;
import com.socialhub.socialaccount.repository.SocialAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ThreadsPublishingService {
    private static final String GRAPH_BASE = "https://graph.threads.net/v1.0/";

    private record Claim(UUID jobId, int attempt, UUID accountId, String text) {
    }

    private record MediaItem(UUID id, String publicUrl, String mediaType) {
    }

    @Value("${app.threads.publisher-batch-size:3}")
    private int batchSize;

    @Value("${app.threads.manual-publishing-enabled:false}")
    private boolean manualPublishingEnabled;

    @Value("${app.threads.token-key:}")
    private String tokenKey;

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final AccessService access;
    private final SocialAccountRepository accounts;
    private final ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * Scheduler and manual endpoints share the same atomic claim and publish path.
     */
    public int processDueBatch() {
        int count = 0;
        for (int i = 0; i < Math.max(0, Math.min(batchSize, 20)); i++) {
            var due = db.query("""
                    SELECT j.id FROM publishing_jobs j
                    JOIN social_accounts a ON a.id=j.social_account_id
                    JOIN posts p ON p.id=j.post_id
                    WHERE j.status='PENDING'
                      AND a.platform='THREADS' AND a.status='CONNECTED'
                      AND a.workspace_id=p.workspace_id
                      AND p.status IN ('READY','SCHEDULED')
                      AND p.approval_status='APPROVED'
                      AND (
                          (j.attempt_count=0
                              AND j.external_post_id IS NULL
                              AND j.published_at IS NULL
                              AND j.published_url IS NULL)
                          OR (
                              j.attempt_count BETWEEN 1 AND 2
                              AND j.external_post_id IS NULL
                              AND j.published_at IS NULL
                              AND j.published_url IS NULL
                              AND EXISTS (
                                  SELECT 1 FROM publishing_reviews r
                                  WHERE r.publishing_job_id=j.id
                                    AND r.attempt_number=j.attempt_count
                                    AND r.decision='CONFIRM_NOT_PUBLISHED'
                                    AND NULLIF(BTRIM(r.evidence),'') IS NOT NULL
                                    AND NULLIF(BTRIM(r.note),'') IS NOT NULL
                              )
                          )
                      )
                      AND COALESCE(j.scheduled_at,j.created_at)<=NOW()
                      AND NOT EXISTS (SELECT 1 FROM post_media m WHERE m.post_id=p.id
                          AND (m.public_url IS NULL OR BTRIM(m.public_url) = '' OR m.media_type NOT IN ('IMAGE','VIDEO')))
                      AND (SELECT COUNT(*) FROM post_media m WHERE m.post_id=p.id) <= 20
                      AND (
                          NOT EXISTS (SELECT 1 FROM post_media vm WHERE vm.post_id=p.id AND vm.media_type='VIDEO')
                          OR (SELECT COUNT(*) FROM post_media vm WHERE vm.post_id=p.id) = 1
                      )
                      AND (NOT EXISTS (SELECT 1 FROM campaign_jobs cj WHERE cj.publishing_job_id=j.id)
                           OR EXISTS (SELECT 1 FROM campaign_jobs cj JOIN campaigns c ON c.id=cj.campaign_id
                                      WHERE cj.publishing_job_id=j.id AND c.status='RUNNING'))
                    ORDER BY j.scheduled_at,j.created_at LIMIT 1
                    """, (rs, n) -> rs.getObject(1, UUID.class));
            if (due.isEmpty()) break;
            var result = publishDue(due.getFirst());
            if (result == null) break;
            count++;
        }
        return count;
    }

    public ThreadsPublishingController.Result publishNow(UUID jobId) {
        if (!manualPublishingEnabled) {
            throw new ApiException(HttpStatus.CONFLICT, "Manual Threads publishing is disabled");
        }
        return publishInternal(jobId, true);
    }

    /**
     * Queue-only, exact-ID entry point. No web endpoint exposes this operation.
     * The caller must explicitly enable the separately gated Phase 2C LIVE path.
     * The existing atomic UPDATE in publishInternal remains the final eligibility guard.
     */
    public ThreadsPublishingController.Result publishQueuedJob(UUID jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("Queue jobId is required");
        }
        return publishInternal(jobId, false);
    }

    private ThreadsPublishingController.Result publishDue(UUID jobId) {
        return publishInternal(jobId, false);
    }

    private ThreadsPublishingController.Result publishInternal(UUID jobId, boolean requireAdmin) {
        var info = db.query("""
                SELECT p.workspace_id, a.platform, a.status AS account_status
                FROM publishing_jobs j
                JOIN posts p ON p.id = j.post_id
                JOIN social_accounts a ON a.id = j.social_account_id
                WHERE j.id = ?
                """, (rs, i) -> Map.of(
                "workspaceId", rs.getObject("workspace_id", UUID.class),
                "platform", rs.getString("platform"),
                "accountStatus", rs.getString("account_status")), jobId);
        if (info.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Publishing job not found");
        if (requireAdmin) access.admin((UUID) info.getFirst().get("workspaceId"));
        if (!"THREADS".equals(info.getFirst().get("platform")))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Job is not a Threads job");
        if (!"CONNECTED".equals(info.getFirst().get("accountStatus")))
            throw new ApiException(HttpStatus.CONFLICT, "Threads account is not connected");

        Claim claim = tx.execute(status -> {
            var rows = db.query("""
                    UPDATE publishing_jobs j
                    SET status='PROCESSING', attempt_count=attempt_count+1,
                        processing_started_at=NOW(), locked_until=NOW()+INTERVAL '5 minutes',
                        next_retry_at=NULL
                    FROM posts p, social_accounts a
                    WHERE j.id=? AND j.post_id=p.id AND j.social_account_id=a.id
                      AND a.platform='THREADS' AND a.status='CONNECTED'
                      AND a.workspace_id=p.workspace_id
                      AND p.status IN ('READY','SCHEDULED')
                      AND p.approval_status='APPROVED'
                      AND (
                          (j.attempt_count=0
                              AND j.external_post_id IS NULL
                              AND j.published_at IS NULL
                              AND j.published_url IS NULL)
                          OR (
                              j.attempt_count BETWEEN 1 AND 2
                              AND j.external_post_id IS NULL
                              AND j.published_at IS NULL
                              AND j.published_url IS NULL
                              AND EXISTS (
                                  SELECT 1 FROM publishing_reviews r
                                  WHERE r.publishing_job_id=j.id
                                    AND r.attempt_number=j.attempt_count
                                    AND r.decision='CONFIRM_NOT_PUBLISHED'
                                    AND NULLIF(BTRIM(r.evidence),'') IS NOT NULL
                                    AND NULLIF(BTRIM(r.note),'') IS NOT NULL
                              )
                          )
                      )
                      AND j.status='PENDING'
                      AND COALESCE(j.scheduled_at,j.created_at)<=NOW()
                      AND NOT EXISTS (SELECT 1 FROM post_media m WHERE m.post_id=p.id
                          AND (m.public_url IS NULL OR BTRIM(m.public_url) = '' OR m.media_type NOT IN ('IMAGE','VIDEO')))
                      AND (SELECT COUNT(*) FROM post_media m WHERE m.post_id=p.id) <= 20
                      AND (
                          NOT EXISTS (SELECT 1 FROM post_media vm WHERE vm.post_id=p.id AND vm.media_type='VIDEO')
                          OR (SELECT COUNT(*) FROM post_media vm WHERE vm.post_id=p.id) = 1
                      )
                      AND (NOT EXISTS (SELECT 1 FROM campaign_jobs cj WHERE cj.publishing_job_id=j.id)
                           OR EXISTS (SELECT 1 FROM campaign_jobs cj JOIN campaigns c ON c.id=cj.campaign_id
                                      WHERE cj.publishing_job_id=j.id AND c.status='RUNNING'))
                    RETURNING j.id, j.attempt_count, j.social_account_id, p.content
                    """, (rs, i) -> new Claim(
                    rs.getObject(1, UUID.class), rs.getInt(2),
                    rs.getObject(3, UUID.class), rs.getString(4)), jobId);
            if (rows.isEmpty()) return null;
            Claim c = rows.getFirst();
            db.update("INSERT INTO publishing_attempts (publishing_job_id,attempt_number,status) VALUES (?,?,'PROCESSING')",
                    c.jobId(), c.attempt());
            return c;
        });
        if (claim == null) throw new ApiException(HttpStatus.CONFLICT,
                "Job must be PENDING, eligible, due now, approved, and contain images or one video");

        boolean requestStarted = false;
        try {
            if (claim.text() == null || claim.text().isBlank()
                    || claim.text().codePointCount(0, claim.text().length()) > 500)
                throw new IllegalArgumentException("Threads text must contain 1-500 characters");
            SocialAccount account = accounts.findById(claim.accountId()).orElseThrow();
            if (account.tokenExpiresAt == null || !account.tokenExpiresAt.isAfter(OffsetDateTime.now()))
                throw new IllegalStateException("Threads token expired; reconnect account");
            String token = decrypt(account.accessTokenEncrypted);
            String userId = account.externalAccountId;
            if (!userId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid Threads user ID");
            String base = GRAPH_BASE + userId;

            requireApprovedForPublish(claim.jobId(), claim.attempt());
            requestStarted = true;
            log.info("[THREADS] Creating container | jobId={}", claim.jobId());
            String creationId = createThreadsContainer(base, token, claim.text(), claim.jobId(), claim.attempt());
            log.info("[THREADS] Container created | jobId={}, creationId={}", claim.jobId(), creationId);
            // Video encoding can take longer than image processing.
            boolean videoPost = getPostMedia(claim.jobId()).stream()
                    .anyMatch(m -> "VIDEO".equals(m.mediaType()));
            awaitContainerReady(creationId, token, claim.jobId(), videoPost ? 60 : 6);
            log.info("[THREADS] Publishing container | jobId={}, creationId={}", claim.jobId(), creationId);
            JsonNode published = post(base + "/threads_publish",
                    "creation_id=" + enc(creationId), token, claim.jobId(), claim.attempt());
            String postId = published.path("id").asText("");
            if (!postId.matches("[0-9]+")) throw new IllegalStateException("Threads published post ID missing");

            // IMPORTANT: persist successful publication BEFORE making any optional metadata request.
            // A failed permalink GET must NEVER turn an already published post into NEEDS_REVIEW.
            tx.executeWithoutResult(s -> {
                int n = db.update("""
                        UPDATE publishing_jobs SET status='PUBLISHED', external_post_id=?, published_url=NULL,
                            published_at=NOW(), last_error=NULL, locked_until=NULL, next_retry_at=NULL
                        WHERE id=? AND status='PROCESSING' AND attempt_count=?
                        """, postId, claim.jobId(), claim.attempt());
                if (n != 1) throw new IllegalStateException("Job update conflict after publishing");
                db.update("""
                        UPDATE publishing_attempts SET status='SUCCESS',finished_at=NOW(),error_message=NULL
                        WHERE publishing_job_id=? AND attempt_number=?
                        """, claim.jobId(), claim.attempt());
                // Link to Excel rows, if the post originated from an Excel import.
                db.update("""
                        UPDATE excel_import_rows e
                        SET processing_status='PUBLISHED'
                        FROM publishing_jobs j
                        WHERE j.id=? AND e.post_id=j.post_id
                        """, claim.jobId());
            });
            log.info("[THREADS] Published | jobId={}, postId={}", claim.jobId(), postId);

            // GET only. Never reissue threads_publish to recover a permalink.
            String finalUrl = null;
            try {
                finalUrl = fetchPermalinkWithRetry(postId, token, claim.jobId());
                final String confirmedUrl = finalUrl;
                tx.executeWithoutResult(s -> {
                    db.update("""
                            UPDATE publishing_jobs
                            SET published_url=?
                            WHERE id=? AND status='PUBLISHED' AND external_post_id=?
                            """, confirmedUrl, claim.jobId(), postId);
                    db.update("""
                            UPDATE excel_import_rows e
                            SET processing_status='PUBLISHED', published_url=?
                            FROM publishing_jobs j
                            WHERE j.id=? AND j.status='PUBLISHED'
                              AND j.external_post_id=? AND e.post_id=j.post_id
                            """, confirmedUrl, claim.jobId(), postId);
                });
                log.info("[THREADS] Permalink saved | jobId={}, postId={}, url={}", claim.jobId(), postId, finalUrl);
            } catch (Exception linkEx) {
                log.warn("[THREADS] Published but permalink could not be saved. "
                        + "Do NOT republish; recover with GET /v1.0/{}?fields=id,permalink. "
                        + "jobId={}, reason={}", postId, claim.jobId(), linkEx.toString());
            }
            return new ThreadsPublishingController.Result(jobId, "PUBLISHED", postId, finalUrl,
                    finalUrl == null ? "Published to Threads; permalink pending reconciliation"
                            : "Published to Threads");
        } catch (Exception ex) {
            log.error("[THREADS] Publishing failed. jobId={}, attempt={}, requestStarted={}",
                    claim.jobId(), claim.attempt(), requestStarted, ex);
            String message = requestStarted
                    ? "Threads result uncertain. Review Backend logs and Threads before any retry."
                    : "Threads validation failed before sending request";
            String jobStatus = requestStarted ? "NEEDS_REVIEW" : "FAILED";
            String attemptStatus = requestStarted ? "UNKNOWN" : "FAILED";
            tx.executeWithoutResult(s -> {
                db.update("""
                        UPDATE publishing_jobs SET status=?,last_error=?,locked_until=NULL,next_retry_at=NULL
                        WHERE id=? AND status='PROCESSING' AND attempt_count=?
                        """, jobStatus, message, claim.jobId(), claim.attempt());
                db.update("""
                        UPDATE publishing_attempts SET status=?,error_message=?,finished_at=NOW()
                        WHERE publishing_job_id=? AND attempt_number=?
                        """, attemptStatus, message, claim.jobId(), claim.attempt());
            });
            return new ThreadsPublishingController.Result(jobId, jobStatus, null, null, message);
        }
    }

    /**
     * Fetch the canonical permalink from Meta using the published media ID.
     */
    private String fetchPermalinkWithRetry(String postId, String token, UUID jobId) throws Exception {
        Exception last = null;
        for (int check = 1; check <= 3; check++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(GRAPH_BASE + postId + "?fields=id,permalink"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + token)
                        .GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("Threads permalink GET returned HTTP " + response.statusCode());
                }
                JsonNode body = json.readTree(response.body());
                if (!postId.equals(body.path("id").asText(""))) {
                    throw new IllegalStateException("Threads permalink GET returned a mismatched post ID");
                }
                String permalink = body.path("permalink").asText("").strip();
                URI uri = URI.create(permalink);
                String host = uri.getHost();
                if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                        || !("threads.net".equalsIgnoreCase(host)
                        || "www.threads.net".equalsIgnoreCase(host)
                        || "threads.com".equalsIgnoreCase(host)
                        || "www.threads.com".equalsIgnoreCase(host))
                        || uri.getUserInfo() != null || uri.getPort() != -1) {
                    throw new IllegalStateException("Meta returned an invalid Threads permalink");
                }
                return permalink;
            } catch (Exception e) {
                last = e;
                log.warn("[THREADS] Permalink not available yet | jobId={}, check={}/3, reason={}",
                        jobId, check, e.toString());
                if (check < 3) Thread.sleep(1500L);
            }
        }
        throw new IllegalStateException("Could not fetch Threads permalink after publication", last);
    }

    private List<MediaItem> getPostMedia(UUID jobId) {
        return db.query("""
                SELECT m.id, m.public_url, m.media_type
                FROM post_media m
                JOIN publishing_jobs j ON j.post_id = m.post_id
                WHERE j.id = ?
                ORDER BY m.display_order ASC, m.id ASC
                """, (rs, i) -> new MediaItem(
                rs.getObject("id", UUID.class), rs.getString("public_url"),
                rs.getString("media_type")), jobId);
    }

    private String createThreadsContainer(String base, String token, String text, UUID jobId, int attempt)
            throws Exception {
        List<MediaItem> media = getPostMedia(jobId);
        if (media.size() > 20) throw new IllegalArgumentException("Threads carousel supports at most 20 images");
        long videoCount = media.stream().filter(m -> "VIDEO".equals(m.mediaType())).count();
        if (videoCount > 0 && (videoCount != 1 || media.size() != 1)) {
            throw new IllegalArgumentException("Use one VIDEO per Threads post; mixed media is not supported yet");
        }
        for (MediaItem item : media) {
            if (!"IMAGE".equals(item.mediaType()) && !"VIDEO".equals(item.mediaType()))
                throw new IllegalArgumentException("Unsupported Threads media type");
            if (item.publicUrl() == null || item.publicUrl().isBlank())
                throw new IllegalArgumentException("Threads media publicUrl is missing");
            URI uri = URI.create(item.publicUrl());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || !"res.cloudinary.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null)
                throw new IllegalArgumentException("Use a public Cloudinary HTTPS media URL");
        }
        if (media.isEmpty()) {
            return requireContainerId(post(base + "/threads",
                    "media_type=TEXT&text=" + enc(text), token, jobId, attempt));
        }
        if (media.size() == 1) {
            MediaItem item = media.getFirst();
            String body = "VIDEO".equals(item.mediaType())
                    ? "media_type=VIDEO&video_url=" + enc(item.publicUrl())
                    : "media_type=IMAGE&image_url=" + enc(item.publicUrl());
            return requireContainerId(post(base + "/threads",
                    body + "&text=" + enc(text), token, jobId, attempt));
        }
        List<String> children = new ArrayList<>();
        for (MediaItem item : media) {
            String childId = requireContainerId(post(base + "/threads",
                    "media_type=IMAGE&image_url=" + enc(item.publicUrl())
                            + "&is_carousel_item=true", token, jobId, attempt));
            log.info("[THREADS] Carousel child created | jobId={}, mediaId={}, childId={}",
                    jobId, item.id(), childId);
            awaitContainerReady(childId, token, jobId);
            children.add(childId);
        }
        return requireContainerId(post(base + "/threads",
                "media_type=CAROUSEL&children=" + enc(String.join(",", children))
                        + "&text=" + enc(text), token, jobId, attempt));
    }

    private static String requireContainerId(JsonNode response) {
        String id = response.path("id").asText("");
        if (!id.matches("[0-9]+")) throw new IllegalStateException("Threads container ID missing or invalid");
        return id;
    }

    private JsonNode post(String url, String form, String token, UUID jobId, int attempt) throws Exception {
        String endpoint = URI.create(url).getPath();
        log.info("[THREADS] Sending request to {}", endpoint);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        requireApprovedForPublish(jobId, attempt);
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String errorCode = "UNKNOWN", errorSubcode = "UNKNOWN", errorType = "UNKNOWN", errorMessage = "UNKNOWN";
            try {
                JsonNode error = json.readTree(response.body()).path("error");
                errorCode = error.path("code").asText("UNKNOWN");
                errorSubcode = error.path("error_subcode").asText("UNKNOWN");
                errorType = error.path("type").asText("UNKNOWN");
                errorMessage = error.path("message").asText("UNKNOWN");
            } catch (Exception parseError) {
                log.warn("[THREADS] Unable to parse API error response");
            }
            log.error("[THREADS] API failed. endpoint={}, status={}, code={}, subcode={}, type={}, message={}",
                    endpoint, response.statusCode(), errorCode, errorSubcode, errorType, errorMessage);
            throw new IllegalStateException("Threads API failed at " + endpoint
                    + " with HTTP " + response.statusCode()
                    + ", code=" + errorCode + ", subcode=" + errorSubcode);
        }
        log.info("[THREADS] Request successful. endpoint={}, status={}", endpoint, response.statusCode());
        return json.readTree(response.body());
    }

    private void requireApprovedForPublish(UUID jobId, int attempt) {
        Boolean approved = db.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM publishing_jobs j
                    JOIN posts p ON p.id = j.post_id
                    JOIN social_accounts a ON a.id = j.social_account_id
                    WHERE j.id = ? AND j.status = 'PROCESSING' AND j.attempt_count = ?
                      AND p.status IN ('READY', 'SCHEDULED') AND p.approval_status = 'APPROVED'
                      AND a.platform = 'THREADS' AND a.status = 'CONNECTED'
                      AND a.workspace_id = p.workspace_id
                )
                """, Boolean.class, jobId, attempt);
        if (!Boolean.TRUE.equals(approved))
            throw new IllegalStateException("Threads publishing blocked: post not approved or job not eligible");
    }

    private String decrypt(String value) throws Exception {
        if (value == null || !value.startsWith("aesgcm:v1:"))
            throw new IllegalArgumentException("Encrypted Threads token missing");
        byte[] key = Base64.getDecoder().decode(tokenKey);
        if (key.length != 32) throw new IllegalArgumentException("Invalid Threads encryption key");
        byte[] packed = Base64.getDecoder().decode(value.substring("aesgcm:v1:".length()));
        if (packed.length < 29) throw new IllegalArgumentException("Encrypted token invalid");
        byte[] iv = Arrays.copyOfRange(packed, 0, 12);
        byte[] ciphertext = Arrays.copyOfRange(packed, 12, packed.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void awaitContainerReady(String creationId, String token, UUID jobId) throws Exception {
        awaitContainerReady(creationId, token, jobId, 6);
    }

    private void awaitContainerReady(String creationId, String token, UUID jobId, int maxChecks) throws Exception {
        final long intervalMillis = 2000L;
        for (int check = 1; check <= maxChecks; check++) {
            JsonNode response = getContainerStatus(creationId, token);
            String state = response.path("status").asText("UNKNOWN");
            log.info("[THREADS] Container readiness | jobId={}, check={}/{}, state={}",
                    jobId, check, maxChecks, state);
            switch (state) {
                case "FINISHED":
                    return;
                case "IN_PROGRESS":
                    if (check < maxChecks) {
                        Thread.sleep(intervalMillis);
                        continue;
                    }
                    throw new IllegalStateException(
                            "Threads container still IN_PROGRESS after " + maxChecks + " checks; no publish attempted");
                case "PUBLISHED":
                    throw new IllegalStateException(
                            "Threads container already PUBLISHED; do not publish again; verify externally");
                case "ERROR", "EXPIRED":
                    throw new IllegalStateException("Threads container status=" + state
                            + "; no publish attempted; details="
                            + response.path("error_message").asText("not supplied"));
                default:
                    throw new IllegalStateException(
                            "Unexpected Threads container status=" + state + "; no publish attempted");
            }
        }
    }

    private JsonNode getContainerStatus(String creationId, String token) throws Exception {
        String url = GRAPH_BASE + enc(creationId) + "?fields=id,status,error_message";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2)
            throw new IllegalStateException("Threads container status check failed: HTTP " + response.statusCode());
        JsonNode result = json.readTree(response.body());
        log.info("[THREADS] Container status | creationId={}, status={}, error={}",
                creationId, result.path("status").asText("UNKNOWN"),
                result.path("error_message").asText(""));
        return result;
    }
}
