
package com.socialhub.publishingjob.bulk.service;

import com.socialhub.publishingjob.bulk.dto.CreateFacebookBulkRunRequest;
import com.socialhub.publishingjob.bulk.dto.FacebookBulkRunResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FacebookBulkRunService {

    private static final int MAX_BULK_SIZE = 100;

    private final JdbcTemplate db;
    private final WorkspaceBulkPermissionService permissions;
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(FacebookBulkRunService.class);

    // Guard LIVE at the shared service layer too. The direct controller calls start() here,
    // so validating flags only in AccountVaultBulkPublishingService is not sufficient.
    @Value("${app.account-vault.bulk-live-start-enabled:false}")
    private boolean liveStartEnabled;

    @Value("${app.automation.live.enabled:false}")
    private boolean exactAutomationLiveEnabled;

    @Value("${app.publisher-enabled:false}")
    private boolean legacyPublisherEnabled;

    @Value("${app.facebook.bulk-enabled:false}")
    private boolean schedulerBulkEnabled;

    @Value("${FACEBOOK_BULK_ENABLED:false}")
    private boolean workerBulkEnabled;

    @Value("${app.facebook.publishing-mode:MOCK}")
    private String schedulerMode;

    @Value("${FACEBOOK_PUBLISHING_MODE:MOCK}")
    private String workerMode;

    @Value("${META_BASE_URL:http://127.0.0.1:9090}")
    private String graphBaseUrl;

    @Value("${app.facebook.bulk-pilot-run-id:}")
    private String pilotRunId;
    @Value("${app.facebook.bulk-av-enabled:false}")
    private boolean bulkAvEnabled;
    @Value("${app.facebook.video-publishing-enabled:false}")
    private boolean videoEnabled;
    @Value("${app.facebook.reels-publishing-enabled:false}")
    private boolean reelEnabled;
    @Value("${app.facebook.video-auto-verify-enabled:false}")
    private boolean videoAutoVerify;
    @Value("${app.facebook.reels-auto-verify-enabled:false}")
    private boolean reelAutoVerify;
    @Value("${FACEBOOK_MAX_IMAGES_PER_POST:10}")
    private int maxImagesPerPost;

    private boolean allowVideo() {
        return bulkAvEnabled && FacebookBulkPilotRules.runId(pilotRunId) != null && videoEnabled && videoAutoVerify;
    }

    private boolean allowReel() {
        return bulkAvEnabled && FacebookBulkPilotRules.runId(pilotRunId) != null && reelEnabled && reelAutoVerify;
    }

    // No token or media URL is returned. The existing read-only preflight API
    // checks the exact immutable grant for each distinct connection.
    public record RunJob(UUID jobId, String pageName, String pageId, UUID connectionId,
                         String facebookUserId, OffsetDateTime scheduledAt, String status,
                         String mediaType, int mediaCount, boolean mediaEligible) {
    }

    @Transactional(readOnly = true)
    public List<RunJob> jobs(UUID userId, UUID runId) {
        BulkRun run = find(runId);
        permissions.requireManager(run.workspaceId(), userId);
        return db.query("""
                        SELECT j.id, a.display_name, a.external_account_id, j.facebook_connection_id,
                               c.facebook_user_id, j.scheduled_at, j.status,
                               COALESCE((SELECT STRING_AGG(DISTINCT m.media_type, ', ' ORDER BY m.media_type)
                                   FROM post_media m WHERE m.post_id=p.id), 'TEXT') AS media_type,
                               (SELECT COUNT(*) FROM post_media m WHERE m.post_id=p.id) AS media_count,
                               (""" + FacebookBulkPilotRules.MEDIA_ELIGIBLE + """
                               ) AS media_eligible
                        FROM facebook_bulk_run_jobs bj
                        JOIN publishing_jobs j ON j.id=bj.publishing_job_id
                        JOIN posts p ON p.id=j.post_id
                        JOIN social_accounts a ON a.id=j.social_account_id
                        LEFT JOIN facebook_page_connections c ON c.id=j.facebook_connection_id AND c.social_account_id=a.id
                        WHERE bj.bulk_run_id=? AND a.workspace_id=? AND p.workspace_id=? AND a.platform='FACEBOOK_PAGE'
                        ORDER BY j.scheduled_at, j.id
                        """, (rs, n) -> new RunJob(rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getString("external_account_id"), rs.getObject("facebook_connection_id", UUID.class),
                        rs.getString("facebook_user_id"), rs.getObject("scheduled_at", OffsetDateTime.class),
                        rs.getString("status"), rs.getString("media_type"), rs.getInt("media_count"), rs.getBoolean("media_eligible")),
                maxImagesPerPost, allowVideo(), allowReel(), runId, run.workspaceId(), run.workspaceId());
    }

    public record RescheduleResult(UUID bulkRunId, String status, int updatedJobs,
                                   OffsetDateTime firstScheduledAt,
                                   OffsetDateTime lastScheduledAt) {
    }

    public record LiveReadiness(UUID bulkRunId, String runStatus,
                                boolean enabled, List<String> blockingFlags) {
    }

    private record RescheduleJob(UUID id, OffsetDateTime scheduledAt,
                                 OffsetDateTime tokenExpiresAt, boolean eligible) {
    }

    /**
     * Move only untouched PENDING jobs in a DRAFT run. The original spacing is preserved.
     * Transaction and row locks prevent a concurrent start/reschedule from splitting a run.
     * No new jobs or bulk runs are inserted, and this method never calls Meta.
     */
    @Transactional
    public RescheduleResult reschedule(UUID userId, UUID runId, OffsetDateTime newFirstScheduledAt) {
        if (runId == null || newFirstScheduledAt == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runId and scheduledAt required");
        }

        Instant now = Instant.now();
        Instant proposedFirst = newFirstScheduledAt.toInstant();
        if (!proposedFirst.isAfter(now.plus(2, ChronoUnit.MINUTES))
                || proposedFirst.isAfter(now.plus(30, ChronoUnit.DAYS))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scheduledAt must be more than 2 minutes ahead and within 30 days");
        }

        BulkRun run = findForUpdate(runId);
        permissions.requireManager(run.workspaceId(), userId);
        if (!"DRAFT".equals(run.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only DRAFT bulk runs can be rescheduled");
        }

        // Lock all jobs in a fixed order. We validate bindings and publishing status before update.
        List<RescheduleJob> runJobs = db.query("""
                SELECT j.id, j.scheduled_at, c.token_expires_at,
                       (j.status = 'PENDING'
                        AND j.attempt_count = 0
                        AND j.external_post_id IS NULL
                        AND j.published_at IS NULL
                        AND j.next_retry_at IS NULL
                        AND j.processing_started_at IS NULL
                        AND j.locked_until IS NULL
                        AND p.workspace_id = ?
                        AND a.workspace_id = ?
                        AND a.platform = 'FACEBOOK_PAGE'
                        AND a.status = 'CONNECTED'
                        AND p.status IN ('READY', 'SCHEDULED')
                        AND p.approval_status = 'APPROVED'
                        AND j.facebook_connection_id IS NOT NULL
                        AND c.id IS NOT NULL
                        AND c.social_account_id = a.id
                        AND c.status = 'CONNECTED'
                        AND NULLIF(BTRIM(c.access_token_encrypted), '') IS NOT NULL
                        AND NOT EXISTS (SELECT 1 FROM campaign_jobs cj
                                        WHERE cj.publishing_job_id = j.id)
                        AND NOT EXISTS (SELECT 1 FROM post_media m
                                        WHERE m.post_id = p.id AND
                                              (m.public_url IS NULL OR BTRIM(m.public_url) = ''))
                       ) AS eligible
                FROM facebook_bulk_run_jobs bj
                JOIN publishing_jobs j ON j.id = bj.publishing_job_id
                JOIN posts p ON p.id = j.post_id
                JOIN social_accounts a ON a.id = j.social_account_id
                LEFT JOIN facebook_page_connections c ON c.id = j.facebook_connection_id
                WHERE bj.bulk_run_id = ?
                ORDER BY j.id
                FOR UPDATE OF j
                """, (rs, rowNum) -> new RescheduleJob(
                rs.getObject("id", UUID.class),
                rs.getObject("scheduled_at", OffsetDateTime.class),
                rs.getObject("token_expires_at", OffsetDateTime.class),
                rs.getBoolean("eligible")
        ), run.workspaceId(), run.workspaceId(), runId);

        Integer linkedCount = db.queryForObject("""
                SELECT COUNT(*) FROM facebook_bulk_run_jobs WHERE bulk_run_id = ?
                """, Integer.class, runId);
        if (linkedCount == null || linkedCount == 0 || runJobs.size() != linkedCount
                || runJobs.size() > MAX_BULK_SIZE
                || new HashSet<>(runJobs.stream().map(RescheduleJob::id).toList()).size() != runJobs.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Bulk run has invalid, duplicated, or missing jobs");
        }
        if (runJobs.stream().anyMatch(job -> !job.eligible() || job.scheduledAt() == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "One or more bulk jobs have been processed or are no longer eligible");
        }

        Instant oldFirst = runJobs.stream()
                .map(job -> job.scheduledAt().toInstant())
                .min(Instant::compareTo)
                .orElseThrow();

        OffsetDateTime last = newFirstScheduledAt;
        for (RescheduleJob job : runJobs) {
            Duration offset = Duration.between(oldFirst, job.scheduledAt().toInstant());
            OffsetDateTime newDue = newFirstScheduledAt.plus(offset);
            if (newDue.toInstant().isAfter(now.plus(30, ChronoUnit.DAYS))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Last job would be scheduled more than 30 days ahead");
            }
            if (job.tokenExpiresAt() != null && !job.tokenExpiresAt().isAfter(newDue)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A bound Facebook OAuth token expires before the new scheduled time");
            }

            int updated = db.update("""
                    UPDATE publishing_jobs
                    SET scheduled_at = ?
                    WHERE id = ? AND status = 'PENDING' AND attempt_count = 0
                      AND external_post_id IS NULL AND published_at IS NULL
                      AND next_retry_at IS NULL AND processing_started_at IS NULL
                      AND locked_until IS NULL
                    """, newDue, job.id());
            if (updated != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Job changed during reschedule; no changes committed");
            }
            if (newDue.isAfter(last)) last = newDue;
        }

        log.info("[BULK RESCHEDULE] runId={} updatedJobs={} first={} last={}",
                runId, runJobs.size(), newFirstScheduledAt, last);
        return new RescheduleResult(runId, "DRAFT", runJobs.size(), newFirstScheduledAt, last);
    }

    @Transactional(readOnly = true)
    public LiveReadiness liveReadiness(UUID userId, UUID runId) {
        if (runId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bulk Run ID is required");
        }
        BulkRun run = find(runId);
        permissions.requireManager(run.workspaceId(), userId);
        List<String> blockers = liveConfigurationBlockers(runId);
        return new LiveReadiness(runId, run.status(), blockers.isEmpty(), List.copyOf(blockers));
    }

    private List<String> liveConfigurationBlockers(UUID runId) {
        List<String> blocking = new ArrayList<>();
        if (!liveStartEnabled) blocking.add("app.account-vault.bulk-live-start-enabled");
        if (exactAutomationLiveEnabled) blocking.add("app.automation.live.enabled must be false");
        if (!legacyPublisherEnabled) blocking.add("app.publisher-enabled");
        if (!schedulerBulkEnabled) blocking.add("app.facebook.bulk-enabled");
        if (!workerBulkEnabled) blocking.add("FACEBOOK_BULK_ENABLED");
        if (!"LIVE".equalsIgnoreCase(schedulerMode)) blocking.add("app.facebook.publishing-mode=LIVE");
        if (!"LIVE".equalsIgnoreCase(workerMode)) blocking.add("FACEBOOK_PUBLISHING_MODE=LIVE");
        if (!"https://graph.facebook.com".equals(graphBaseUrl))
            blocking.add("META_BASE_URL=https://graph.facebook.com");
        try {
            UUID pilot = FacebookBulkPilotRules.runId(pilotRunId);
            if (pilot != null && !pilot.equals(runId)) blocking.add("Lượt đăng không thuộc pilot được phép");
            if (bulkAvEnabled && pilot == null) blocking.add("app.facebook.bulk-pilot-run-id");
        } catch (IllegalArgumentException ex) {
            blocking.add("app.facebook.bulk-pilot-run-id không hợp lệ");
        }
        if (maxImagesPerPost < 1) blocking.add("FACEBOOK_MAX_IMAGES_PER_POST");
        return blocking;
    }

    private void requireExplicitLiveConfiguration(UUID runId) {
        List<String> blocking = liveConfigurationBlockers(runId);
        if (!blocking.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Facebook BULK LIVE is not enabled: " + String.join(", ", blocking));
        }
    }

    @Transactional
    public FacebookBulkRunResponse create(
            UUID userId,
            CreateFacebookBulkRunRequest request
    ) {
        log.info("[BULK CREATE] ===== START ===== userId={}", userId);

        try {
            // 1. Validate request
            if (request == null
                    || request.workspaceId() == null
                    || request.jobIds() == null
                    || request.jobIds().isEmpty()) {

                log.warn("[BULK CREATE] Invalid request: missing workspaceId or jobIds");

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Workspace and job IDs are required"
                );
            }

            UUID workspaceId = request.workspaceId();
            List<UUID> ids = request.jobIds();

            log.info(
                    "[BULK CREATE] Request | workspaceId={} | jobIds={}",
                    workspaceId,
                    ids
            );

            // 2. Check workspace permission
            log.info("[BULK CREATE] Checking workspace permission...");

            permissions.requireManager(workspaceId, userId);

            log.info("[BULK CREATE] Workspace permission OK");

            // 3. Validate job IDs
            if (ids.size() > MAX_BULK_SIZE
                    || ids.contains(null)
                    || new HashSet<>(ids).size() != ids.size()) {

                log.warn(
                        "[BULK CREATE] Invalid job list | size={} | ids={}",
                        ids.size(),
                        ids
                );

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Invalid bulk job list"
                );
            }

            List<UUID> sortedIds = ids.stream()
                    .sorted()
                    .toList();

            log.info("[BULK CREATE] Sorted jobs={}", sortedIds);

            // 4. Check eligibility for each job
            for (UUID jobId : sortedIds) {
                log.info("[BULK CREATE] Checking eligibility | jobId={}", jobId);

                List<UUID> eligible = db.query("""
                                SELECT j.id
                                FROM publishing_jobs j
                                JOIN posts p
                                    ON p.id = j.post_id
                                JOIN social_accounts a
                                    ON a.id = j.social_account_id
                                WHERE j.id = ?
                                  AND p.workspace_id = ?
                                  AND a.workspace_id = ?
                                  AND a.platform = 'FACEBOOK_PAGE'
                                  AND a.status = 'CONNECTED'
                                  AND p.status IN ('READY', 'SCHEDULED')
                                  AND p.approval_status = 'APPROVED'
                                  AND j.status = 'PENDING'
                                  AND j.attempt_count = 0
                                  AND j.external_post_id IS NULL
                                
                                  -- DRAFT may contain AV while pilot disabled, but not malformed/mixed media.
                                  AND (
                                """ + FacebookBulkPilotRules.MEDIA_ELIGIBLE + """
                                  )
                                
                                  -- Prevent putting the same job into multiple bulk runs.
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM facebook_bulk_run_jobs bj
                                      WHERE bj.publishing_job_id = j.id
                                  )
                                
                                  -- Prevent conflicts with campaign jobs.
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM campaign_jobs cj
                                      WHERE cj.publishing_job_id = j.id
                                  )
                                
                                FOR UPDATE OF j
                                """,
                        (rs, rowNum) ->
                                rs.getObject("id", UUID.class),
                        jobId,
                        workspaceId,
                        workspaceId,
                        maxImagesPerPost, true, true
                );

                log.info(
                        "[BULK CREATE] Eligibility result | jobId={} | eligibleCount={}",
                        jobId,
                        eligible.size()
                );

                if (eligible.size() != 1) {
                    log.warn(
                            "[BULK CREATE] Job not eligible | jobId={}",
                            jobId
                    );

                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Job is not eligible: " + jobId
                    );
                }
            }

            log.info("[BULK CREATE] All {} jobs are eligible", sortedIds.size());

            // 5. Generate Bulk Run ID
            UUID runId = UUID.randomUUID();

            log.info(
                    "[BULK CREATE] Inserting bulk run | runId={} | workspaceId={}",
                    runId,
                    workspaceId
            );

            // 6. Insert Bulk Run
            int insertedRun = db.update("""
                            INSERT INTO facebook_bulk_runs (
                                id,
                                workspace_id,
                                status
                            )
                            VALUES (?, ?, 'DRAFT')
                            """,
                    runId,
                    workspaceId
            );

            log.info(
                    "[BULK CREATE] Bulk Run INSERT result | runId={} | affectedRows={}",
                    runId,
                    insertedRun
            );

            if (insertedRun != 1) {
                throw new IllegalStateException(
                        "Expected 1 inserted bulk run, got " + insertedRun
                );
            }

            // 7. Link jobs to Bulk Run
            for (UUID jobId : sortedIds) {
                log.info(
                        "[BULK CREATE] Inserting bulk job link | runId={} | jobId={}",
                        runId,
                        jobId
                );

                int insertedLink = db.update("""
                                INSERT INTO facebook_bulk_run_jobs (
                                    bulk_run_id,
                                    publishing_job_id
                                )
                                VALUES (?, ?)
                                """,
                        runId,
                        jobId
                );

                log.info(
                        "[BULK CREATE] Bulk Job INSERT result | jobId={} | affectedRows={}",
                        jobId,
                        insertedLink
                );

                if (insertedLink != 1) {
                    throw new IllegalStateException(
                            "Expected 1 inserted bulk job link for " + jobId
                    );
                }
            }

            // 8. Build response
            FacebookBulkRunResponse response = new FacebookBulkRunResponse(
                    runId,
                    workspaceId,
                    "DRAFT",
                    sortedIds.size()
            );

            log.info(
                    "[BULK CREATE] SUCCESS | runId={} | totalJobs={} | status=DRAFT",
                    runId,
                    sortedIds.size()
            );

            return response;

        } catch (ResponseStatusException e) {
            log.warn(
                    "[BULK CREATE] Business validation failed | status={} | reason={}",
                    e.getStatusCode(),
                    e.getReason(),
                    e
            );
            throw e;

        } catch (org.springframework.dao.DataAccessException e) {
            log.error(
                    "[BULK CREATE] DATABASE ERROR | userId={} | rootCause={}",
                    userId,
                    e.getMostSpecificCause().getMessage(),
                    e
            );
            throw e;

        } catch (Exception e) {
            log.error(
                    "[BULK CREATE] UNEXPECTED ERROR | userId={}",
                    userId,
                    e
            );
            throw e;

        } finally {
            log.info("[BULK CREATE] ===== END =====");
        }
    }


    @Transactional
    public FacebookBulkRunResponse start(
            UUID userId,
            UUID runId
    ) {
        log.info(
                "[BULK START] ===== START ===== userId={} | runId={}",
                userId,
                runId
        );

        try {
            // 1. Kiểm tra Bulk Run
            if (runId == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Bulk Run ID is required"
                );
            }

            log.info("[BULK START] Finding Bulk Run for update...");

            BulkRun run = findForUpdate(runId);

            log.info(
                    "[BULK START] Bulk Run found | workspaceId={} | status={}",
                    run.workspaceId(),
                    run.status()
            );

            // 2. Kiểm tra quyền quản lý Workspace
            permissions.requireManager(run.workspaceId(), userId);

            log.info("[BULK START] Workspace permission OK");

            // The direct /api/facebook/bulk-runs/{runId}/start endpoint must NOT bypass LIVE flags.
            requireExplicitLiveConfiguration(runId);

            // 3. Chỉ cho phép start DRAFT
            if (!"DRAFT".equals(run.status())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Only DRAFT bulk runs can be started"
                );
            }

            // 4. Đếm tổng số Jobs được liên kết
            Integer totalJobs = db.queryForObject("""
                            SELECT COUNT(*)
                            FROM facebook_bulk_run_jobs
                            WHERE bulk_run_id = ?
                            """,
                    Integer.class,
                    runId
            );

            log.info(
                    "[BULK START] Total linked Jobs={}",
                    totalJobs
            );

            if (totalJobs == null || totalJobs == 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Bulk Run has no Publishing Jobs"
                );
            }

            // Serialize with reschedule and the publisher's updates of these jobs.
            List<UUID> lockedJobs = db.query("""
                    SELECT j.id FROM facebook_bulk_run_jobs bj
                    JOIN publishing_jobs j ON j.id = bj.publishing_job_id
                    WHERE bj.bulk_run_id = ?
                    ORDER BY j.id
                    FOR UPDATE OF j
                    """, (rs, rowNum) -> rs.getObject("id", UUID.class), runId);
            if (lockedJobs.size() != totalJobs) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Bulk Run jobs changed or are missing");
            }

            // 5. Kiểm tra lại điều kiện của các Job
            Integer invalidCount = db.queryForObject("""
                            SELECT COUNT(*)
                            FROM facebook_bulk_run_jobs bj
                            JOIN publishing_jobs j
                                ON j.id = bj.publishing_job_id
                            JOIN posts p
                                ON p.id = j.post_id
                            JOIN social_accounts a
                                ON a.id = j.social_account_id
                            WHERE bj.bulk_run_id = ?
                              AND (
                                   j.status <> 'PENDING'
                                   OR j.attempt_count <> 0
                                   OR j.external_post_id IS NOT NULL
                                   OR j.published_at IS NOT NULL
                                   OR j.published_url IS NOT NULL
                                   OR j.next_retry_at IS NOT NULL
                                   OR j.processing_started_at IS NOT NULL
                                   OR j.locked_until IS NOT NULL
                                   -- Never start a run whose due time is past / too close.
                                   OR j.scheduled_at IS NULL
                                   OR j.scheduled_at <= NOW() + INTERVAL '2 minutes'
                                   OR j.scheduled_at > NOW() + INTERVAL '30 days'
                                   -- Re-check immutable OAuth binding even via direct API.
                                   OR j.facebook_connection_id IS NULL
                                   OR NOT EXISTS (
                                       SELECT 1 FROM facebook_page_connections c
                                       WHERE c.id = j.facebook_connection_id
                                         AND c.social_account_id = a.id
                                         AND c.status = 'CONNECTED'
                                         AND NULLIF(BTRIM(c.access_token_encrypted), '') IS NOT NULL
                                         AND (c.token_expires_at IS NULL OR c.token_expires_at > j.scheduled_at)
                                         AND NOT EXISTS (SELECT 1 FROM facebook_authorized_identities fi
                                             WHERE fi.workspace_id=a.workspace_id AND fi.facebook_user_id=c.facebook_user_id
                                               AND (fi.health_status IN ('DISCONNECTED','REAUTH_REQUIRED')
                                                 OR fi.token_expires_at <= j.scheduled_at))
                                   )
                            
                                   -- Không cho phép Job thuộc Campaign.
                                   OR EXISTS (
                                       SELECT 1
                                       FROM campaign_jobs cj
                                       WHERE cj.publishing_job_id = j.id
                                   )
                            
                                   -- Workspace phải hợp lệ.
                                   OR p.workspace_id <> ?
                                   OR a.workspace_id <> ?
                            
                                   -- Chỉ Facebook Page đã kết nối.
                                   OR a.platform <> 'FACEBOOK_PAGE'
                                   OR a.status <> 'CONNECTED'
                            
                                   -- Bài viết phải sẵn sàng và được duyệt.
                                   OR p.status NOT IN ('READY', 'SCHEDULED')
                                   OR p.approval_status IS DISTINCT FROM 'APPROVED'
                            
                                   OR NOT (
                            """ + FacebookBulkPilotRules.MEDIA_ELIGIBLE + """
                                   )
                              )
                            """,
                    Integer.class,
                    runId,
                    run.workspaceId(),
                    run.workspaceId(),
                    maxImagesPerPost, allowVideo(), allowReel()
            );

            log.info(
                    "[BULK START] Validation | totalJobs={} | invalidJobs={}",
                    totalJobs,
                    invalidCount
            );

            if (invalidCount == null || invalidCount > 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Một số lịch không đủ điều kiện: thời gian, quyền Facebook hoặc loại media chưa được bật/hỗ trợ. Kiểm tra trước khi kích hoạt."
                );
            }

            // 6. Chuyển Bulk Run sang RUNNING
            log.info(
                    "[BULK START] Updating Bulk Run to RUNNING | runId={}",
                    runId
            );

            int updated = db.update("""
                            UPDATE facebook_bulk_runs
                            SET status = 'RUNNING',
                                started_at = NOW()
                            WHERE id = ?
                              AND status = 'DRAFT'
                            """,
                    runId
            );

            log.info(
                    "[BULK START] Update result | affectedRows={}",
                    updated
            );

            if (updated != 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Bulk run state changed"
                );
            }

            // 7. Trả kết quả
            FacebookBulkRunResponse response = getResponse(runId);

            log.info(
                    "[BULK START] SUCCESS | runId={} | status={} | totalJobs={}",
                    runId,
                    response.status(),
                    response.totalJobs()
            );

            return response;

        } catch (ResponseStatusException e) {
            log.warn(
                    "[BULK START] Business validation failed | status={} | reason={}",
                    e.getStatusCode(),
                    e.getReason(),
                    e
            );
            throw e;

        } catch (org.springframework.dao.DataAccessException e) {
            log.error(
                    "[BULK START] DATABASE ERROR | runId={} | rootCause={}",
                    runId,
                    e.getMostSpecificCause().getMessage(),
                    e
            );
            throw e;

        } catch (Exception e) {
            log.error(
                    "[BULK START] UNEXPECTED ERROR | runId={}",
                    runId,
                    e
            );
            throw e;

        } finally {
            log.info("[BULK START] ===== END =====");
        }
    }


    @Transactional
    public FacebookBulkRunResponse cancel(
            UUID userId,
            UUID runId
    ) {
        BulkRun run = findForUpdate(runId);

        permissions.requireManager(run.workspaceId(), userId);

        if (!"DRAFT".equals(run.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only DRAFT bulk runs can be cancelled safely"
            );
        }

        db.update("""
                        UPDATE facebook_bulk_runs
                        SET status = 'CANCELLED',
                            finished_at = NOW()
                        WHERE id = ?
                          AND status = 'DRAFT'
                        """,
                runId
        );

        return getResponse(runId);
    }

    @Transactional(readOnly = true)
    public FacebookBulkRunResponse get(
            UUID userId,
            UUID runId
    ) {
        BulkRun run = find(runId);

        permissions.requireManager(run.workspaceId(), userId);

        return getResponse(runId);
    }

    private record BulkRun(
            UUID workspaceId,
            String status
    ) {
    }

    private BulkRun find(UUID runId) {
        return db.query("""
                        SELECT workspace_id, status
                        FROM facebook_bulk_runs
                        WHERE id = ?
                        """,
                rs -> {
                    if (!rs.next()) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Bulk run not found"
                        );
                    }

                    return new BulkRun(
                            rs.getObject("workspace_id", UUID.class),
                            rs.getString("status")
                    );
                },
                runId
        );
    }

    private BulkRun findForUpdate(UUID runId) {
        return db.query("""
                        SELECT workspace_id, status
                        FROM facebook_bulk_runs
                        WHERE id = ?
                        FOR UPDATE
                        """,
                rs -> {
                    if (!rs.next()) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Bulk run not found"
                        );
                    }

                    return new BulkRun(
                            rs.getObject("workspace_id", UUID.class),
                            rs.getString("status")
                    );
                },
                runId
        );
    }

    private FacebookBulkRunResponse getResponse(UUID runId) {
        return db.queryForObject("""
                        SELECT
                            r.id,
                            r.workspace_id,
                            r.status,
                            COUNT(bj.id) AS total_jobs
                        FROM facebook_bulk_runs r
                        LEFT JOIN facebook_bulk_run_jobs bj
                            ON bj.bulk_run_id = r.id
                        WHERE r.id = ?
                        GROUP BY r.id, r.workspace_id, r.status
                        """,
                (rs, rowNum) -> new FacebookBulkRunResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("total_jobs")
                ),
                runId
        );
    }
}
