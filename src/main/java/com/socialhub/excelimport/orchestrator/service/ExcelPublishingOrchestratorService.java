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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
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

    // Lead time for completing Cloudinary/Drive preparation and creating/starting Bulk Runs.
    // Jobs keep their source future schedule; too-close schedules are rejected, not shifted.
    @Value("${app.excel.orchestrator.minimum-preparation-minutes:10}")
    private long minimumPreparationMinutes;

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
        List<String> scheduleWarnings = validateLeadTime(plannedPreview);
        if (dry) {
            return new Result(workspaceId, importId, true, token,
                    plannedPreview.selectedRows(), 0, plannedPreview.blocked(),
                    plannedPreview.alreadyPlanned(), plannedPreview.notSelected(), 0, 0,
                    countUnplanned(workspaceId, importId), List.of(),
                    mergeErrors(plannedPreview.errors(), scheduleWarnings), "PREVIEW");
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
        if (!scheduleWarnings.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Schedule is too close for safe preparation: " + scheduleWarnings.getFirst()
                            + ". Choose a later start time and preview again");
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

        // Bind each run to the exact source rows JUST created in this confirmation.
        // The bridge normally selects the oldest eligible jobs from an entire import;
        // without this guard, an unrelated older PENDING job can be started instead.
        Set<Integer> expectedRows = new LinkedHashSet<>();
        for (var item : committed.rows()) {
            if ("JOB_CREATED".equals(item.action())) expectedRows.add(item.sourceRowNumber());
        }
        if (expectedRows.size() != committed.createdJobs()) {
            errors.add("Created jobs do not match unique batch rows; inspect jobs before retrying");
        }

        // Media processing can take time. Re-check remaining lead time AFTER job creation
        // but BEFORE starting any run. A partial batch must never be reported successful.
        if (errors.isEmpty()) {
            Set<Integer> remainingRows = new LinkedHashSet<>(expectedRows);
            while (!remainingRows.isEmpty()) {
                int maxForThisRun = Math.min(jobsPerRun, remainingRows.size());
                ExcelBulkRunBridgeService.Preview bulkPreview;
                try {
                    bulkPreview = bridge.preview(workspaceId, importId, maxForThisRun);
                } catch (RuntimeException ex) {
                    errors.add("Bulk preview error: " + shortError(ex));
                    break;
                }

                Set<Integer> selectedRows = new LinkedHashSet<>();
                for (var candidate : bulkPreview.jobs()) {
                    selectedRows.add(candidate.sourceRowNumber());
                }
                if (bulkPreview.selectedJobs() != maxForThisRun
                        || selectedRows.size() != maxForThisRun
                        || !remainingRows.containsAll(selectedRows)) {
                    errors.add("Bulk eligibility mismatch; expected " + maxForThisRun
                            + " jobs from the newly created batch, got "
                            + bulkPreview.selectedJobs() + " jobs at rows " + selectedRows
                            + ". No further Bulk Run started; review unlinked jobs");
                    break;
                }
                try {
                    var preparedRun = bridge.prepare(workspaceId, importId, operatorId,
                            new ExcelBulkRunBridgeService.PrepareRequest(
                                    maxForThisRun, bulkPreview.previewToken(), true));
                    UUID runId = preparedRun.bulkRunId();
                    prepared += preparedRun.selectedJobs();
                    runs.add(new RunResult(runId, "DRAFT", preparedRun.selectedJobs()));
                    // The request's explicit LIVE confirmation authorizes starting this run.
                    FacebookBulkRunResponse startedRun = bulkRuns.start(operatorId, runId);
                    started++;
                    runs.set(runs.size() - 1,
                            new RunResult(runId, startedRun.status(), startedRun.totalJobs()));
                    remainingRows.removeAll(selectedRows);
                } catch (RuntimeException ex) {
                    // A DRAFT may exist, and a START might have been accepted remotely.
                    // Reconcile before attempting further operations; never blind retry.
                    errors.add("Bulk setup/start stopped: " + shortError(ex)
                            + ". Inspect facebook_bulk_runs before retrying");
                    break;
                }
            }
        }
        int remaining = countUnplanned(workspaceId, importId);
        String status = errors.isEmpty() && committed.createdJobs() == prepared
                && started == runs.size()
                ? "RUNNING" : "PARTIAL_REVIEW_REQUIRED";
        return new Result(workspaceId, importId, false, token,
                committed.selectedRows(), committed.createdJobs(), committed.blocked(),
                committed.alreadyPlanned(), committed.notSelected(), prepared, started,
                remaining, List.copyOf(runs), List.copyOf(errors), status);
    }

    private List<String> validateLeadTime(ExcelAutoPublishService.BatchPlanResult preview) {
        List<String> errors = new ArrayList<>();
        Instant threshold = Instant.now().plus(Duration.ofMinutes(
                Math.max(3, minimumPreparationMinutes)));
        for (var row : preview.rows()) {
            if (!("KEEP_ORIGINAL".equals(row.action())
                    || "RESCHEDULED".equals(row.action()))) continue;
            if (row.scheduledAt() == null || !row.scheduledAt().toInstant().isAfter(threshold)) {
                errors.add("row " + row.sourceRowNumber() + " must be scheduled after "
                        + threshold + " (preparation lead time)");
            }
        }
        return errors;
    }

    private static List<String> mergeErrors(List<String> original, List<String> additional) {
        List<String> result = new ArrayList<>(original);
        result.addAll(additional);
        return List.copyOf(result);
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
                  AND (r.processing_status IS NULL OR r.processing_status NOT IN ('PUBLISHED', 'AUTO_SCHEDULED'))
                  AND (r.processing_status IS NULL OR r.processing_status NOT LIKE 'SKIPPED_%')
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
