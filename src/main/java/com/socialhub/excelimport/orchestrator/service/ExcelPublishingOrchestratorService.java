package com.socialhub.excelimport.orchestrator.service;

import com.socialhub.common.ApiException;
import com.socialhub.excelimport.service.implement.ExcelBulkRunBridgeService;
import com.socialhub.excelimport.service.implement.ExcelAutoPublishService;
import com.socialhub.publishingjob.bulk.dto.FacebookBulkRunResponse;
import com.socialhub.publishingjob.bulk.service.FacebookBulkRunService;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Synchronous safety-bounded MVP. No row numbers or job IDs are hardcoded.
 * Each call takes the next eligible rows from the chosen import.
 * Not a durable/background orchestrator yet; see README before production use.
 */
@Service
@RequiredArgsConstructor
public class ExcelPublishingOrchestratorService {

    @Value("${APP_EXCEL_ORCHESTRATOR_ENABLED:false}")
    private boolean enabled;

    // Limits the LIVE impact and avoids long HTTP calls while media is fetched.
    private static final int MAX_ROWS_PER_CONFIRMATION = 50;
    private static final int MAX_JOBS_PER_BULK_RUN = 5;

    private final AccessService access;
    private final JdbcTemplate db;
    private final ExcelAutoPublishService autoPlan;
    private final ExcelBulkRunBridgeService bridge;
    private final FacebookBulkRunService bulkRuns;

    public record Request(
            LocalDate startDate,
            LocalTime startTime,
            Integer intervalMinutes,
            Integer maxPostsPerDay,
            String timezone,
            Integer maxRows,
            Integer jobsPerRun,
            Boolean dryRun,
            Boolean confirmLive,
            String previewToken
    ) {
    }

    public record RunResult(UUID bulkRunId, String status, int jobs) {
    }

    public record Result(
            UUID workspaceId,
            UUID importId,
            boolean dryRun,
            String previewToken,
            int selectedRows,
            int createdJobs,
            int blocked,
            int alreadyPlanned,
            int notSelected,
            int preparedJobs,
            int startedRuns,
            int remainingUnplannedRows,
            List<RunResult> runs,
            List<String> errors,
            String status
    ) {
    }

    public Result execute(UUID workspaceId, UUID importId, UUID operatorId, Request request) {
        // This is deliberately executed on the authenticated API request thread.
        // ExcelAutoPublishService checks the current principal via AccessService.
        access.admin(workspaceId);
        if (!enabled) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Excel orchestrator disabled; set APP_EXCEL_ORCHESTRATOR_ENABLED=true");
        }
        if (request == null || request.startDate() == null || request.startTime() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "startDate and startTime required");
        }
        int rows = request.maxRows() == null ? 5 : request.maxRows();
        int jobsPerRun = request.jobsPerRun() == null ? 5 : request.jobsPerRun();

        if (rows < 1 || rows > MAX_ROWS_PER_CONFIRMATION
                || jobsPerRun < 1 || jobsPerRun > MAX_JOBS_PER_BULK_RUN) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "maxRows=1..50 and jobsPerRun=1..5 required"
            );
        }
        boolean dry = !Boolean.FALSE.equals(request.dryRun());
        var previewRequest = planRequest(request, rows, true, false, null);
        var plannedPreview = autoPlan.planBatch(workspaceId, importId, previewRequest);
        String token = token(workspaceId, importId, rows, jobsPerRun,
                plannedPreview.previewToken());
        if (dry) {
            return new Result(workspaceId, importId, true, token,
                    plannedPreview.selectedRows(), 0, plannedPreview.blocked(),
                    plannedPreview.alreadyPlanned(), plannedPreview.notSelected(), 0, 0,
                    countUnplanned(workspaceId, importId), List.of(), plannedPreview.errors(),
                    "PREVIEW");
        }
        if (!Boolean.TRUE.equals(request.confirmLive())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "confirmLive=true is mandatory: this request can activate Facebook LIVE");
        }
        if (request.previewToken() == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                request.previewToken().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Preview changed; run dryRun=true again and use its token");
        }
        if (plannedPreview.selectedRows() == 0 || plannedPreview.blocked() > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "No clean scheduling preview. Resolve blocked rows or use next eligible batch");
        }

        // The first step may invoke Drive/Cloudinary. It can finish partially.
        var committed = autoPlan.planBatch(workspaceId, importId,
                planRequest(request, rows, false, true, plannedPreview.previewToken()));

        List<RunResult> runs = new ArrayList<>();
        List<String> errors = new ArrayList<>(committed.errors());
        int prepared = 0;
        int started = 0;

        // Max 50 Jobs, partitioned into <=5 per Facebook Bulk Run.
        // A preview is recomputed before each create to avoid reusing already-linked Jobs.
        for (int n = 0; n < rows; n += jobsPerRun) {
            ExcelBulkRunBridgeService.Preview bulkPreview;
            try {
                bulkPreview = bridge.preview(workspaceId, importId, jobsPerRun);
            } catch (RuntimeException ex) {
                errors.add("Bulk preview error: " + shortError(ex));
                break;
            }
            if (bulkPreview.selectedJobs() == 0) break;
            try {
                var preparedRun = bridge.prepare(workspaceId, importId, operatorId,
                        new ExcelBulkRunBridgeService.PrepareRequest(
                                jobsPerRun, bulkPreview.previewToken(), true));
                UUID runId = preparedRun.bulkRunId();
                prepared += preparedRun.selectedJobs();
                // Persist the DRAFT run ID in the response before attempting START.
                // If START fails, callers can reconcile it rather than creating duplicates.
                runs.add(new RunResult(runId, "DRAFT", preparedRun.selectedJobs()));
                // Explicit confirmation from this request is what authorizes start.
                FacebookBulkRunResponse startedRun = bulkRuns.start(operatorId, runId);
                started++;
                runs.set(runs.size() - 1,
                        new RunResult(runId, startedRun.status(), startedRun.totalJobs()));
            } catch (RuntimeException ex) {
                // Never retry START blindly; a DRAFT Run may have been created.
                errors.add("Bulk run setup/start stopped: " + shortError(ex) +
                        ". Inspect facebook_bulk_runs before retrying.");
                break;
            }
        }
        int remaining = countUnplanned(workspaceId, importId);
        String status = errors.isEmpty() && committed.createdJobs() == prepared
                ? "RUNNING" : "PARTIAL_REVIEW_REQUIRED";
        return new Result(workspaceId, importId, false, token,
                committed.selectedRows(), committed.createdJobs(), committed.blocked(),
                committed.alreadyPlanned(), committed.notSelected(), prepared, started,
                remaining, List.copyOf(runs), List.copyOf(errors), status);
    }

    private ExcelAutoPublishService.BatchPlanRequest planRequest(
            Request request, int rows, boolean dry, boolean confirm, String token) {
        return new ExcelAutoPublishService.BatchPlanRequest(
                "KEEP_FUTURE_RESCHEDULE_EXPIRED", request.startDate(), request.startTime(),
                request.intervalMinutes(), request.maxPostsPerDay(), request.timezone(),
                rows, dry, confirm, token);
    }

    private int countUnplanned(UUID workspaceId, UUID importId) {
        Integer count = db.queryForObject("""
                SELECT COUNT(*) FROM excel_import_rows r
                JOIN excel_imports e ON e.id = r.import_id
                JOIN posts p ON p.id = r.post_id
                WHERE e.workspace_id = ? AND e.id = ?
                  AND r.processing_status NOT IN ('PUBLISHED', 'AUTO_SCHEDULED')
                  AND r.processing_status NOT LIKE 'SKIPPED_%'
                  AND NOT EXISTS (
                    SELECT 1 FROM publishing_jobs j
                    WHERE j.post_id = p.id AND j.social_account_id = e.social_account_id
                  )
                """, Integer.class, workspaceId, importId);
        return count == null ? 0 : count;
    }

    private static String token(UUID workspace, UUID imp, int rows, int perRun,
                                String inner) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((workspace + "|" + imp + "|" + rows + "|" + perRun + "|" + inner)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String shortError(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName()
                : message.substring(0, Math.min(240, message.length()));
    }
}
