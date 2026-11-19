package com.socialhub.excelimport.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.publishingjob.bulk.dto.CreateFacebookBulkRunRequest;
import com.socialhub.publishingjob.bulk.dto.FacebookBulkRunResponse;
import com.socialhub.publishingjob.bulk.service.FacebookBulkRunService;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Bridges already-created Excel Publishing Jobs into an existing Facebook Bulk Run.
 * No new Publishing Jobs, Post approvals or Meta calls are made here.
 * Deliberate LIVE safety cap: at most five jobs and DRAFT only.
 */
@Service
@RequiredArgsConstructor
public class ExcelBulkRunBridgeService {

    private static final int TEST_MAX_JOBS = 5;

    @Value("${APP_EXCEL_BULK_BRIDGE_ENABLED:false}")
    private boolean bridgeEnabled;

    private final AccessService access;
    private final JdbcTemplate db;
    private final FacebookBulkRunService bulkRuns;

    public record CandidateJob(UUID jobId, int sourceRowNumber, OffsetDateTime scheduledAt) {
    }

    public record Preview(UUID importId,
                          int requestedJobs,
                          int selectedJobs,
                          List<CandidateJob> jobs,
                          String previewToken,
                          UUID bulkRunId,
                          String status) {
    }

    public record PrepareRequest(Integer maxJobs,
                                 String previewToken,
                                 Boolean confirmPrepare) {
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID workspaceId, UUID importId, int maxJobs) {
        checkPermissionAndInput(workspaceId, importId, maxJobs);
        List<CandidateJob> candidates = getEligibleJobs(workspaceId, importId, maxJobs);
        return buildPreview(workspaceId, importId, maxJobs, candidates);
    }

    /**
     * The caller must explicitly confirm the matching preview. This creates
     * one DRAFT Bulk Run using the existing guarded BulkRunService.create().
     * It does NOT call start() and does NOT publish to Facebook.
     */
    @Transactional
    public Preview prepare(UUID workspaceId, UUID importId, UUID operatorId,
                           PrepareRequest request) {
        if (request == null || request.maxJobs() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "maxJobs is required");
        }
        int maxJobs = request.maxJobs();
        checkPermissionAndInput(workspaceId, importId, maxJobs);
        if (!Boolean.TRUE.equals(request.confirmPrepare())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "confirmPrepare=true is required; this operation creates a DRAFT Bulk Run");
        }
        List<CandidateJob> candidates = getEligibleJobs(workspaceId, importId, maxJobs);
        Preview current = buildPreview(workspaceId, importId, maxJobs, candidates);
        if (candidates.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "No eligible pending Excel Jobs. Check approval, media, OAuth and schedule (>15 min ahead)");
        }
        if (request.previewToken() == null
                || !MessageDigest.isEqual(
                request.previewToken().getBytes(StandardCharsets.UTF_8),
                current.previewToken().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Eligible jobs changed; call GET /bulk-runs/preview again");
        }

        // The existing service performs locking, workspace/permission checks,
        // deduplication and final eligibility verification in this transaction.
        FacebookBulkRunResponse created = bulkRuns.create(operatorId,
                new CreateFacebookBulkRunRequest(workspaceId,
                        candidates.stream().map(CandidateJob::jobId).toList()));

        return new Preview(importId, maxJobs, candidates.size(),
                List.copyOf(candidates), current.previewToken(),
                created.bulkRunId(), created.status());
    }

    private void checkPermissionAndInput(UUID workspaceId, UUID importId, int maxJobs) {
        access.admin(workspaceId);
        if (!bridgeEnabled) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Excel Bulk bridge disabled; set APP_EXCEL_BULK_BRIDGE_ENABLED=true");
        }
        if (maxJobs < 1 || maxJobs > TEST_MAX_JOBS) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "For this test, maxJobs must be between 1 and 5");
        }
        Boolean exists = db.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM excel_imports e
                    WHERE e.id = ? AND e.workspace_id = ?
                      AND e.social_account_id IS NOT NULL
                )
                """, Boolean.class, importId, workspaceId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "Excel import not found in workspace or not bound to an account");
        }
    }

    private List<CandidateJob> getEligibleJobs(UUID workspaceId, UUID importId, int maxJobs) {
        // Preview is SELECT-only. A 15-minute margin avoids accidentally
        // activating a run with an already-overdue job during this test.
        return db.query("""
                SELECT j.id AS job_id, r.source_row_number, j.scheduled_at
                FROM excel_imports e
                JOIN excel_import_rows r ON r.import_id = e.id
                JOIN publishing_jobs j
                  ON j.post_id = r.post_id
                 AND j.social_account_id = e.social_account_id
                JOIN posts p ON p.id = j.post_id
                JOIN social_accounts a ON a.id = j.social_account_id
                JOIN facebook_page_connections c
                  ON c.id = j.facebook_connection_id
                 AND c.social_account_id = a.id
                WHERE e.id = ?
                  AND e.workspace_id = ?
                  AND p.workspace_id = ?
                  AND a.workspace_id = ?
                  AND a.platform = 'FACEBOOK_PAGE'
                  AND a.status = 'CONNECTED'
                  AND c.status = 'CONNECTED'
                  AND NULLIF(BTRIM(c.access_token_encrypted), '') IS NOT NULL
                  AND (c.token_expires_at IS NULL
                       OR c.token_expires_at > j.scheduled_at)
                  AND r.processing_status = 'AUTO_SCHEDULED'
                  AND p.status = 'SCHEDULED'
                  AND p.approval_status = 'APPROVED'
                  AND j.status = 'PENDING'
                  AND j.attempt_count = 0
                  AND j.external_post_id IS NULL
                  AND j.published_at IS NULL
                  AND j.next_retry_at IS NULL
                  AND j.processing_started_at IS NULL
                  AND j.locked_until IS NULL
                  AND j.scheduled_at > NOW() + INTERVAL '15 minutes'
                  AND j.scheduled_at < NOW() + INTERVAL '30 days'
                  AND NOT EXISTS (
                      SELECT 1 FROM facebook_bulk_run_jobs bj
                      WHERE bj.publishing_job_id = j.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM campaign_jobs cj
                      WHERE cj.publishing_job_id = j.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM post_media m
                      WHERE m.post_id = p.id
                        AND (m.public_url IS NULL OR BTRIM(m.public_url) = '')
                  )
                ORDER BY j.scheduled_at, r.source_row_number, j.id
                LIMIT ?
                """, (rs, index) -> new CandidateJob(
                rs.getObject("job_id", UUID.class),
                rs.getInt("source_row_number"),
                rs.getObject("scheduled_at", OffsetDateTime.class)
        ), importId, workspaceId, workspaceId, workspaceId, maxJobs);
    }

    private Preview buildPreview(UUID workspaceId, UUID importId, int maxJobs,
                                 List<CandidateJob> candidates) {
        List<String> fragments = new ArrayList<>();
        fragments.add("excel-bulk-v1");
        fragments.add(workspaceId.toString());
        fragments.add(importId.toString());
        fragments.add(Integer.toString(maxJobs));
        for (CandidateJob job : candidates) {
            fragments.add(job.jobId() + ":" + job.sourceRowNumber() + ":"
                    + job.scheduledAt().toInstant());
        }
        String token = sha256(String.join("|", fragments));
        return new Preview(importId, maxJobs, candidates.size(),
                List.copyOf(candidates), token, null, "PREVIEW");
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
