package com.socialhub.excelimport.orchestrator.service;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Accept LIVE confirmation once, after a fresh read-only 50-row preview.
 */
@Service
@RequiredArgsConstructor
public class ExcelOrchestratorQueueService {
    private final ExcelPublishingOrchestratorService orchestrator;
    private final AccessService access;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public record Accepted(UUID queueId, String status, int requestedRows,
                           @JsonSerialize(using = ViDisplayTextSerializer.class) String message) {
    }

    public record QueueStatus(UUID queueId, UUID workspaceId, UUID importId, String status,
                              int requestedRows, int processedRows, int createdJobs,
                              int preparedJobs, int startedRuns,
                              @JsonSerialize(using = ViDisplayTextSerializer.class) String lastError,
                              String createdAt, String updatedAt,
                              int publishedJobs, int totalLinkedJobs, List<UUID> bulkRunIds) {
    }

    public Accepted enqueue(UUID workspaceId, UUID importId, UUID operatorId,
                            ExcelPublishingOrchestratorService.Request request) {
        access.admin(workspaceId);
        if (request == null || !Boolean.FALSE.equals(request.dryRun())
                || !Boolean.TRUE.equals(request.confirmLive())
                || request.previewToken() == null || request.previewToken().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Confirm with dryRun=false, confirmLive=true and a fresh previewToken");
        }
        int rows = request.maxRows() == null ? 5 : request.maxRows();
        int perRun = request.jobsPerRun() == null ? 5 : request.jobsPerRun();
        if (rows < 1 || rows > 50 || perRun < 1 || perRun > 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "maxRows=1..50, jobsPerRun=1..5");
        }
        ExcelPublishingOrchestratorService.Request safePreview = new ExcelPublishingOrchestratorService.Request(
                request.startDate(), request.startTime(), request.intervalMinutes(),
                request.maxPostsPerDay(), request.timezone(), rows, perRun,
                true, false, null);
        var preview = orchestrator.execute(workspaceId, importId, operatorId, safePreview);
        if (!MessageDigest.isEqual(preview.previewToken().getBytes(StandardCharsets.UTF_8),
                request.previewToken().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.CONFLICT, "Preview changed. Request a fresh preview first");
        }
        if (preview.selectedRows() != rows || preview.blocked() > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Not enough eligible clean rows for this confirmed run");
        }
        // Fail before accepting a second request: a pending or review-required run
        // must not be silently overwritten by another confirmation.
        Boolean active = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM excel_orchestrator_queue
                              WHERE import_id = ? AND status IN ('QUEUED','RUNNING','REVIEW_REQUIRED'))
                """, Boolean.class, importId);
        if (Boolean.TRUE.equals(active)) {
            throw new ApiException(HttpStatus.CONFLICT, "An orchestrator run already needs processing or review");
        }
        try {
            UUID id = UUID.randomUUID();
            db.update("""
                    INSERT INTO excel_orchestrator_queue
                    (id,workspace_id,import_id,operator_id,request_payload,requested_rows)
                    VALUES (?,?,?,?,?::jsonb,?)
                    """, id, workspaceId, importId, operatorId, mapper.writeValueAsString(toPersistedPayload(request)), rows);
            return new Accepted(id, "QUEUED", rows,
                    "Accepted for background preparation, not yet published to Facebook");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not persist orchestrator request", ex);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "Another run already exists for this import");
        }
    }

    // The project defines a plain Jackson 2 ObjectMapper without JavaTimeModule.
    // Persist ISO strings rather than attempting to serialize LocalDate/LocalTime.
    private static Map<String, Object> toPersistedPayload(
            ExcelPublishingOrchestratorService.Request request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("startDate", request.startDate().toString());
        values.put("startTime", request.startTime().toString());
        values.put("intervalMinutes", request.intervalMinutes());
        values.put("maxPostsPerDay", request.maxPostsPerDay());
        values.put("timezone", request.timezone());
        values.put("jobsPerRun", request.jobsPerRun());
        return values;
    }

    public QueueStatus get(UUID workspaceId, UUID importId, UUID queueId) {
        access.admin(workspaceId);
        return db.query("""
                        SELECT q.*, COALESCE((SELECT COUNT(*) FROM facebook_bulk_run_jobs bj
                                 JOIN excel_orchestrator_bulk_runs x ON x.bulk_run_id = bj.bulk_run_id
                                 WHERE x.queue_id=q.id),0) AS total_linked,
                               COALESCE((SELECT COUNT(*) FROM facebook_bulk_run_jobs bj
                                 JOIN excel_orchestrator_bulk_runs x ON x.bulk_run_id = bj.bulk_run_id
                                 JOIN publishing_jobs j ON j.id = bj.publishing_job_id
                                 WHERE x.queue_id=q.id AND j.status='PUBLISHED'),0) AS published
                        FROM excel_orchestrator_queue q
                        WHERE q.id=? AND q.workspace_id=? AND q.import_id=?
                        """, (rs, n) -> new QueueStatus(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getObject("import_id", UUID.class), rs.getString("status"),
                        rs.getInt("requested_rows"), rs.getInt("processed_rows"),
                        rs.getInt("created_jobs"), rs.getInt("prepared_jobs"),
                        rs.getInt("started_runs"), rs.getString("last_error"),
                        rs.getString("created_at"), rs.getString("updated_at"),
                        rs.getInt("published"), rs.getInt("total_linked"),
                        db.query("SELECT bulk_run_id FROM excel_orchestrator_bulk_runs WHERE queue_id=? ORDER BY bulk_run_id",
                                (r, i) -> r.getObject(1, UUID.class), queueId)
                ), queueId, workspaceId, importId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Orchestrator run not found"));
    }
}
