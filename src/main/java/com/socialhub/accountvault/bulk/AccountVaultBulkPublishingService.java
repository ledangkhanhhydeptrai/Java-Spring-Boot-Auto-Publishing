package com.socialhub.accountvault.bulk;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;
import com.socialhub.common.ApiException;
import com.socialhub.publishingjob.bulk.dto.CreateFacebookBulkRunRequest;
import com.socialhub.publishingjob.bulk.dto.FacebookBulkRunResponse;
import com.socialhub.publishingjob.bulk.service.FacebookBulkRunService;
import com.socialhub.publishingjob.dto.PublishingJobDto;
import com.socialhub.publishingjob.repository.PublishingJobRepository;
import com.socialhub.facebookoauth.repository.FacebookPageConnectionRepository;
import com.socialhub.facebookoauth.entity.FacebookPageConnection;
import com.socialhub.publishingjob.service.implement.PublishingJobServiceImpl;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Transactional assembly of already-approved Posts and OAuth-authorized accounts.
 * FACEBOOK_PAGE -> existing FacebookBulkRunService DRAFT run (requires explicit start).
 * THREADS -> existing scheduled Publishing Jobs (no new publisher or bypass).
 * This class DOES NOT call Meta or RabbitMQ and never relaxes a LIVE allowlist.
 */
@Service
@RequiredArgsConstructor
public class AccountVaultBulkPublishingService {

    public record BulkRequest(String platform, List<UUID> postIds,
                              List<UUID> accountIds, OffsetDateTime scheduledAt,
                              boolean confirmPublish, Map<UUID, UUID> facebookConnectionIds) {
        // Preserve existing Java callers and old request JSON without the optional map.
        public BulkRequest(String platform, List<UUID> postIds, List<UUID> accountIds,
                           OffsetDateTime scheduledAt, boolean confirmPublish) {
            this(platform, postIds, accountIds, scheduledAt, confirmPublish, null);
        }
    }

    public record PreviewIssue(UUID postId, UUID accountId,
                               @JsonSerialize(using = ViDisplayTextSerializer.class) String reason) {
    }

    public record Preview(String platform, int postCount, int accountCount,
                          int totalJobs, boolean eligible, List<PreviewIssue> issues) {
    }

    public record Scheduled(String platform, UUID facebookBulkRunId,
                            String state, int totalJobs, List<UUID> jobIds,
                            OffsetDateTime firstScheduledAt,
                            OffsetDateTime lastScheduledAt) {
    }

    public record FacebookProgress(UUID bulkRunId, String status, int total,
                                   int pending, int processing, int published,
                                   int failed, int needsReview) {
    }

    private static final int MAX_JOBS_PER_BATCH = 100;
    private static final int MAX_ISSUES = 50;
    private final JdbcTemplate db;
    private final AccessService access;
    private final PublishingJobServiceImpl jobService;
    private final FacebookBulkRunService facebookBulk;
    private final PublishingJobRepository jobs;
    private final FacebookPageConnectionRepository facebookConnections;

    @Value("${app.account-vault.bulk-preparation-enabled:false}")
    private boolean preparationEnabled;

    @Value("${app.account-vault.bulk-live-start-enabled:false}")
    private boolean facebookLiveStartEnabled;

    @Value("${app.automation.live.enabled:false}")
    private boolean exactAutomationLiveEnabled;

    @Value("${app.publisher-enabled:false}")
    private boolean legacyFacebookEnabled;

    @Value("${app.threads.publisher-enabled:false}")
    private boolean threadsSchedulerEnabled;

    @Value("${app.facebook.publishing-mode:MOCK}")
    private String schedulerMode;

    @Value("${FACEBOOK_PUBLISHING_MODE:MOCK}")
    private String workerMode;

    @Value("${app.facebook.bulk-enabled:false}")
    private boolean schedulerBulkEnabled;

    @Value("${FACEBOOK_BULK_ENABLED:false}")
    private boolean workerBulkEnabled;

    @Value("${META_BASE_URL:http://127.0.0.1:9090}")
    private String graphBaseUrl;

    private String validate(UUID workspaceId, BulkRequest request) {
        access.admin(workspaceId);
        if (request == null || request.platform() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "platform required");
        }
        String platform = request.platform().trim().toUpperCase(Locale.ROOT);
        if (!List.of("FACEBOOK_PAGE", "THREADS").contains(platform)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only FACEBOOK_PAGE or THREADS supported");
        }
        if (request.postIds() == null || request.accountIds() == null
                || request.postIds().isEmpty() || request.accountIds().isEmpty()
                || request.postIds().stream().anyMatch(java.util.Objects::isNull)
                || request.accountIds().stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(request.postIds()).size() != request.postIds().size()
                || new HashSet<>(request.accountIds()).size() != request.accountIds().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Provide nonempty, distinct postIds/accountIds");
        }
        Map<UUID, UUID> bindings = request.facebookConnectionIds();
        if (bindings != null) {
            if (!"FACEBOOK_PAGE".equals(platform)
                    || bindings.size() != request.accountIds().size()
                    || !bindings.keySet().equals(new HashSet<>(request.accountIds()))
                    || bindings.values().stream().anyMatch(java.util.Objects::isNull)) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Vui lòng chọn đúng một kết nối Facebook cho mỗi Fanpage đã chọn.");
            }
        }
        long total = (long) request.postIds().size() * request.accountIds().size();
        if (total > MAX_JOBS_PER_BATCH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "At most 100 post-account pairs per batch");
        }
        if (request.scheduledAt() == null
                || !request.scheduledAt().isAfter(OffsetDateTime.now().plusMinutes(2))
                || request.scheduledAt().isAfter(OffsetDateTime.now().plusDays(30))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "scheduledAt must be at least 2 minutes ahead and within 30 days");
        }
        return platform;
    }

    /**
     * Resolve a single eligible grant. An explicit choice never falls back.
     */
    private List<FacebookPageConnection> eligibleConnections(UUID accountId, OffsetDateTime due) {
        return facebookConnections.findEligible(accountId).stream()
                .filter(c -> c.getAccessTokenEncrypted() != null && !c.getAccessTokenEncrypted().isBlank())
                .filter(c -> c.getTokenExpiresAt() == null || c.getTokenExpiresAt().isAfter(due))
                .toList();
    }

    private FacebookPageConnection resolveConnection(BulkRequest request, UUID accountId, OffsetDateTime due) {
        UUID requested = request.facebookConnectionIds() == null ? null : request.facebookConnectionIds().get(accountId);
        List<FacebookPageConnection> eligible = eligibleConnections(accountId, due);
        if (requested != null) {
            return eligible.stream().filter(c -> requested.equals(c.getId())).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "Kết nối Facebook đã chọn không còn hợp lệ cho Fanpage này hoặc hết hạn trước giờ đăng."));
        }
        if (eligible.size() != 1) {
            throw new ApiException(HttpStatus.CONFLICT, eligible.isEmpty()
                    ? "Fanpage chưa có kết nối Facebook hợp lệ đến giờ đăng."
                    : "Fanpage có nhiều tài khoản cấp quyền. Vui lòng chọn tài khoản Facebook thực hiện đăng.");
        }
        // Compatibility for callers without an explicit choice, only when unambiguous.
        return eligible.getFirst();
    }

    /**
     * Read-only: full eligibility check before the user explicitly confirms.
     */
    @Transactional(readOnly = true)
    public Preview preview(UUID workspaceId, BulkRequest request) {
        String platform = validate(workspaceId, request);
        List<PreviewIssue> issues = new ArrayList<>();

        for (UUID accountId : request.accountIds()) {
            Boolean connected = db.queryForObject("""
                            SELECT EXISTS (
                                SELECT 1 FROM social_accounts a
                                WHERE a.id=? AND a.workspace_id=?
                                  AND a.platform=? AND a.status='CONNECTED'
                                  AND (
                                      (a.platform='THREADS'
                                         AND NULLIF(BTRIM(a.access_token_encrypted),'') IS NOT NULL
                                         AND a.token_expires_at IS NOT NULL
                                         AND a.token_expires_at > ?)
                                      OR
                                      (a.platform='FACEBOOK_PAGE' AND EXISTS (
                                            SELECT 1 FROM facebook_page_connections c
                                            WHERE c.social_account_id=a.id AND c.status='CONNECTED'
                                              AND NULLIF(BTRIM(c.access_token_encrypted),'') IS NOT NULL
                                              AND (c.token_expires_at IS NULL OR c.token_expires_at > ?)
                                      ))
                                  )
                            )
                            """, Boolean.class, accountId, workspaceId, platform,
                    request.scheduledAt(), request.scheduledAt());
            if (!Boolean.TRUE.equals(connected)) {
                addIssue(issues, null, accountId,
                        "Account unavailable, OAuth token invalid/expired or wrong workspace/platform");
            } else if ("FACEBOOK_PAGE".equals(platform)) {
                try {
                    resolveConnection(request, accountId, request.scheduledAt());
                } catch (ApiException ex) {
                    addIssue(issues, null, accountId, ex.getMessage());
                }
            }
        }

        for (UUID postId : request.postIds()) {
            Boolean eligiblePost = db.queryForObject("""
                            SELECT EXISTS (
                                SELECT 1 FROM posts p
                                WHERE p.id=? AND p.workspace_id=?
                                  AND p.status IN ('READY','SCHEDULED')
                                  AND p.approval_status='APPROVED'
                                  AND NULLIF(BTRIM(p.content),'') IS NOT NULL
                                  AND (? <> 'THREADS' OR CHAR_LENGTH(p.content) <= 500)
                                  AND NOT EXISTS (
                                     SELECT 1 FROM post_media m
                                     WHERE m.post_id=p.id AND
                                           (m.media_type IS DISTINCT FROM 'IMAGE'
                                            OR NULLIF(BTRIM(m.public_url),'') IS NULL)
                                  )
                                  AND (SELECT COUNT(*) FROM post_media m WHERE m.post_id=p.id) <= ?
                            )
                            """, Boolean.class, postId, workspaceId, platform,
                    "FACEBOOK_PAGE".equals(platform) ? 10 : 20);
            if (!Boolean.TRUE.equals(eligiblePost) || !mediaUrlsReady(postId, platform)) {
                addIssue(issues, postId, null,
                        "Post not approved/READY, text/media invalid or wrong workspace");
                continue;
            }
            for (UUID accountId : request.accountIds()) {
                Boolean exists = db.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM publishing_jobs
                            WHERE post_id=? AND social_account_id=?
                        )
                        """, Boolean.class, postId, accountId);
                if (Boolean.TRUE.equals(exists)) {
                    addIssue(issues, postId, accountId,
                            "This post/account pair already has a Publishing Job");
                }
            }
        }

        return new Preview(platform, request.postIds().size(), request.accountIds().size(),
                request.postIds().size() * request.accountIds().size(),
                issues.isEmpty(), List.copyOf(issues));
    }

    /**
     * Match the essential URI rules enforced by the existing platform publishers.
     */
    private boolean mediaUrlsReady(UUID postId, String platform) {
        List<String> urls = db.query("SELECT public_url FROM post_media WHERE post_id=?",
                (rs, row) -> rs.getString(1), postId);
        for (String url : urls) {
            try {
                URI uri = URI.create(url);
                String host = uri.getHost();
                if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                        || uri.getUserInfo() != null || uri.getPort() != -1
                        || uri.getFragment() != null) return false;
                host = host.toLowerCase(Locale.ROOT);
                if (host.equals("localhost") || host.endsWith(".localhost")
                        || host.endsWith(".local") || host.endsWith(".internal")
                        || host.matches("[0-9.]+") || host.contains(":")) return false;
                if ("THREADS".equals(platform) && !"res.cloudinary.com".equals(host)) {
                    return false;
                }
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return true;
    }

    private void addIssue(List<PreviewIssue> issues, UUID postId, UUID accountId, String reason) {
        if (issues.size() < MAX_ISSUES) issues.add(new PreviewIssue(postId, accountId, reason));
    }

    /**
     * FACEBOOK_PAGE: atomically creates Jobs and DRAFT bulk run; never starts it.
     * THREADS: explicitly schedules Jobs for the existing Threads scheduler; at their
     * due time execution still depends on the operator's existing publisher flags.
     * Database uniqueness on (post_id, social_account_id) prevents concurrent duplicates.
     */
    @Transactional
    public Scheduled schedule(UUID workspaceId, UUID operatorUserId, BulkRequest request) {
        if (!preparationEnabled) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Vault bulk preparation disabled; enable app.account-vault.bulk-preparation-enabled in a tested environment");
        }
        if (request == null || !request.confirmPublish()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "confirmPublish=true required for creation of LIVE publishing jobs");
        }
        Preview result = preview(workspaceId, request);
        if ("THREADS".equals(result.platform())
                && (!threadsSchedulerEnabled || exactAutomationLiveEnabled)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Threads scheduling requires tested legacy Threads publisher and automation.live.enabled=false; do not queue into a disabled worker");
        }
        if (!result.eligible()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Batch is not eligible; run preview to inspect issues");
        }

        Map<UUID, UUID> fixedConnections = new HashMap<>();
        if ("FACEBOOK_PAGE".equals(result.platform())) {
            for (UUID accountId : request.accountIds()) {
                fixedConnections.put(accountId, resolveConnection(request, accountId, request.scheduledAt()).getId());
            }
        }
        List<UUID> createdIds = new ArrayList<>();
        OffsetDateTime first = null;
        OffsetDateTime last = null;
        int index = 0;
        Map<UUID, OffsetDateTime> lastByAccount = new HashMap<>();
        // Global jitter 15s, and >=90s between Jobs for each account.
        // These are conservative application caps, NOT official Meta quotas.
        for (UUID postId : request.postIds()) {
            for (UUID accountId : request.accountIds()) {
                OffsetDateTime due = request.scheduledAt().plusSeconds(index++ * 15L);
                OffsetDateTime prior = lastByAccount.get(accountId);
                if (prior != null && due.isBefore(prior.plusSeconds(90))) {
                    due = prior.plusSeconds(90);
                }
                lastByAccount.put(accountId, due);
                if ("THREADS".equals(result.platform())) {
                    Boolean tokenStillValid = db.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM social_accounts
                            WHERE id=? AND workspace_id=? AND platform='THREADS'
                              AND status='CONNECTED' AND token_expires_at > ?)
                            """, Boolean.class, accountId, workspaceId, due);
                    if (!Boolean.TRUE.equals(tokenStillValid)) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "Threads access token expires before the job's scheduled time");
                    }
                }
                PublishingJobDto.View created = jobService.create(postId, accountId, due);
                if ("FACEBOOK_PAGE".equals(result.platform())) {
                    UUID connectionId = fixedConnections.get(accountId);
                    var eligible = eligibleConnections(accountId, due).stream()
                            .filter(c -> connectionId.equals(c.getId()))
                            .findFirst()
                            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                                    "Kết nối Facebook đã chọn không hợp lệ tại giờ đăng. Không tự chuyển sang tài khoản khác."));
                    var job = jobs.findById(created.id()).orElseThrow();
                    job.setFacebookConnection(eligible);
                    jobs.save(job);
                }
                createdIds.add(created.id());
                if (first == null || due.isBefore(first)) first = due;
                if (last == null || due.isAfter(last)) last = due;
            }
        }

        if ("FACEBOOK_PAGE".equals(result.platform())) {
            // Reuse the existing independently reviewed Facebook Bulk lifecycle.
            // If any guard fails, the entire transaction rolls back, including Jobs.
            FacebookBulkRunResponse run = facebookBulk.create(operatorUserId,
                    new CreateFacebookBulkRunRequest(workspaceId, createdIds));
            return new Scheduled(result.platform(), run.bulkRunId(), "DRAFT",
                    createdIds.size(), List.copyOf(createdIds), first, last);
        }

        // Explicit scheduling is the approval action for Threads. No direct Meta API call.
        return new Scheduled(result.platform(), null, "SCHEDULED",
                createdIds.size(), List.copyOf(createdIds), first, last);
    }


    /**
     * Fail-closed LIVE start; no change to existing Facebook or Threads workers.
     */
    @Transactional
    public FacebookBulkRunResponse startFacebook(UUID workspaceId, UUID operatorUserId, UUID runId) {
        access.admin(workspaceId);
        FacebookBulkRunResponse current = facebookBulk.get(operatorUserId, runId);
        if (!workspaceId.equals(current.workspaceId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Bulk run not found in workspace");
        }
        if (!facebookLiveStartEnabled || exactAutomationLiveEnabled || !legacyFacebookEnabled
                || !schedulerBulkEnabled || !workerBulkEnabled
                || !"LIVE".equalsIgnoreCase(schedulerMode)
                || !"LIVE".equalsIgnoreCase(workerMode)
                || !"https://graph.facebook.com".equals(graphBaseUrl)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Facebook BULK LIVE not explicitly enabled or conflicts with exact-job automation. "
                            + "Review README flags and test on an isolated environment first");
        }
        Integer unsafe = db.queryForObject("""
                SELECT COUNT(*) FROM facebook_bulk_run_jobs bj
                JOIN publishing_jobs j ON j.id=bj.publishing_job_id
                JOIN social_accounts a ON a.id=j.social_account_id
                LEFT JOIN facebook_page_connections c ON c.id=j.facebook_connection_id
                WHERE bj.bulk_run_id=? AND (
                  a.status IS DISTINCT FROM 'CONNECTED' OR
                  j.facebook_connection_id IS NULL OR
                  c.id IS NULL OR c.social_account_id IS DISTINCT FROM a.id OR
                  c.status IS DISTINCT FROM 'CONNECTED' OR
                  NULLIF(BTRIM(c.access_token_encrypted),'') IS NULL OR
                  (c.token_expires_at IS NOT NULL AND c.token_expires_at <= j.scheduled_at)
                )
                """, Integer.class, runId);
        if (unsafe == null || unsafe != 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "One or more bound Facebook OAuth connections are invalid/expired");
        }
        return facebookBulk.start(operatorUserId, runId);
    }

    @Transactional(readOnly = true)
    public FacebookProgress progress(UUID workspaceId, UUID operatorUserId, UUID runId) {
        access.workspace(workspaceId);
        FacebookBulkRunResponse run = facebookBulk.get(operatorUserId, runId);
        if (!workspaceId.equals(run.workspaceId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Bulk run not found in workspace");
        }
        return db.queryForObject("""
                SELECT COUNT(*) AS total,
                  COUNT(*) FILTER (WHERE j.status='PENDING') AS pending,
                  COUNT(*) FILTER (WHERE j.status='PROCESSING') AS processing,
                  COUNT(*) FILTER (WHERE j.status='PUBLISHED') AS published,
                  COUNT(*) FILTER (WHERE j.status='FAILED') AS failed,
                  COUNT(*) FILTER (WHERE j.status='NEEDS_REVIEW') AS needs_review
                FROM facebook_bulk_run_jobs bj
                JOIN publishing_jobs j ON j.id=bj.publishing_job_id
                WHERE bj.bulk_run_id=?
                """, (rs, n) -> new FacebookProgress(runId, run.status(),
                rs.getInt("total"), rs.getInt("pending"),
                rs.getInt("processing"), rs.getInt("published"),
                rs.getInt("failed"), rs.getInt("needs_review")), runId);
    }
}
