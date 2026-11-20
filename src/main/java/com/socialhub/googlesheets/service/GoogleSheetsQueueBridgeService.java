package com.socialhub.googlesheets.service;

import com.socialhub.common.ApiException;
import com.socialhub.excelimport.orchestrator.service.ExcelOrchestratorQueueService;
import com.socialhub.excelimport.orchestrator.service.ExcelPublishingOrchestratorService;
import com.socialhub.excelimport.service.implement.ExcelAutoPublishService;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Guard an existing Excel import against a linked, synced Google Sheet.
 * Does not create Posts. LIVE is allowed only through an explicit queue confirmation.
 */
@Service
@RequiredArgsConstructor
public class GoogleSheetsQueueBridgeService {
    private final AccessService access;
    private final JdbcTemplate db;
    private final ExcelAutoPublishService autoPlan;
    private final ExcelPublishingOrchestratorService orchestrator;
    private final ExcelOrchestratorQueueService queue;

    public record Chosen(int sheetRowNumber, UUID postId, String rowHash) { }
    public record Preview(UUID connectionId, UUID importId, int selectedRows,
                          String previewToken, String orchestratorPreviewToken,
                          List<Integer> sourceRows, String status) { }
    public record ConfirmRequest(UUID importId, ExcelPublishingOrchestratorService.Request schedule,
                                 String sheetPreviewToken) { }

    private ExcelAutoPublishService.BatchPlanRequest batchRequest(
            ExcelPublishingOrchestratorService.Request req, int maxRows) {
        return new ExcelAutoPublishService.BatchPlanRequest(
                "KEEP_FUTURE_RESCHEDULE_EXPIRED", req.startDate(), req.startTime(),
                req.intervalMinutes(), req.maxPostsPerDay(), req.timezone(), maxRows,
                true, false, null);
    }

    private List<Chosen> eligible(UUID workspaceId, UUID connectionId, UUID importId,
                                  ExcelPublishingOrchestratorService.Request req, int maxRows) {
        access.admin(workspaceId);
        if (req == null || req.startDate() == null || req.startTime() == null
                || maxRows < 1 || maxRows > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Valid schedule and maxRows=1..50 required");
        }
        Boolean linked = db.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM google_sheet_connections c
                JOIN excel_imports e ON e.workspace_id=c.workspace_id
                WHERE c.id=? AND c.workspace_id=? AND e.id=? AND e.social_account_id IS NOT NULL
                AND c.sync_status='SYNCED' AND c.last_synced_at IS NOT NULL)
            """, Boolean.class, connectionId, workspaceId, importId);
        if (!Boolean.TRUE.equals(linked)) {
            throw new ApiException(HttpStatus.CONFLICT, "Google Sheet and Excel import must be synced in same workspace");
        }
        var plan = autoPlan.planBatch(workspaceId, importId, batchRequest(req,maxRows));
        if (plan.selectedRows() != maxRows || plan.blocked() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Selected Excel schedule has blocked or missing rows");
        }
        List<Chosen> chosen = new ArrayList<>();
        for (var row : plan.rows()) {
            if (!"KEEP_ORIGINAL".equals(row.action()) && !"RESCHEDULED".equals(row.action())) continue;
            // Match exact Excel source row and Post content, not just source_status or row position.
            List<Chosen> matches = db.query("""
                SELECT s.source_row_number, p.id AS post_id, s.row_hash
                FROM google_sheet_staged_rows s
                JOIN excel_import_rows r ON r.import_id=? AND r.source_row_number=s.source_row_number
                JOIN posts p ON p.id=r.post_id AND p.workspace_id=?
                WHERE s.connection_id=? AND s.source_row_number=? AND p.id=?
                  AND s.present_in_sheet=TRUE AND s.publish_eligible=TRUE
                  AND s.source_status='Chưa đăng'
                  AND BTRIM(s.source_published_url)=''
                  AND s.content=p.content
                  AND NOT EXISTS (
                    SELECT 1 FROM publishing_jobs j
                    WHERE j.post_id=p.id AND j.social_account_id=(
                        SELECT social_account_id FROM excel_imports WHERE id=? AND workspace_id=?))
                """, (rs,n) -> new Chosen(rs.getInt(1),rs.getObject(2,UUID.class),rs.getString(3)),
                importId, workspaceId,connectionId,row.sourceRowNumber(),row.postId(),importId,workspaceId);
            if (matches.size()!=1) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "Sheet / Excel mismatch or existing job at source row " + row.sourceRowNumber());
            }
            chosen.add(matches.getFirst());
        }
        if (chosen.size()!=maxRows) throw new ApiException(HttpStatus.CONFLICT,"Not all selected rows can be reconciled with Sheet");
        return List.copyOf(chosen);
    }

    private String snapshotToken(UUID workspaceId, UUID connectionId, UUID importId,
                                 String orchestratorToken, List<Chosen> rows) {
        StringBuilder signature = new StringBuilder("google-sheet-queue-v1|")
                .append(workspaceId).append('|').append(connectionId).append('|')
                .append(importId).append('|').append(orchestratorToken).append('|');
        for (Chosen row:rows) signature.append(row.sheetRowNumber()).append(':')
                .append(row.postId()).append(':').append(row.rowHash()).append(';');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(signature.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID workspaceId, UUID connectionId, UUID importId,
                           UUID operatorId, ExcelPublishingOrchestratorService.Request request) {
        int rows=request.maxRows()==null?5:request.maxRows();
        List<Chosen> chosen=eligible(workspaceId,connectionId,importId,request,rows);
        var orchestratorReq = new ExcelPublishingOrchestratorService.Request(
                request.startDate(),request.startTime(),request.intervalMinutes(),request.maxPostsPerDay(),
                request.timezone(),rows,request.jobsPerRun(),true,false,null);
        var result=orchestrator.execute(workspaceId,importId,operatorId,orchestratorReq);
        if (result.selectedRows()!=rows || result.blocked()>0) {
            throw new ApiException(HttpStatus.CONFLICT,"Orchestrator preview changed");
        }
        return new Preview(connectionId,importId,rows,
                snapshotToken(workspaceId,connectionId,importId,result.previewToken(),chosen),
                result.previewToken(),chosen.stream().map(Chosen::sheetRowNumber).toList(),"PREVIEW");
    }

    /** One DB transaction: queue request and its sheet row mapping are committed together. */
    @Transactional
    public ExcelOrchestratorQueueService.Accepted confirm(UUID workspaceId, UUID connectionId,
                                                           UUID operatorId, ConfirmRequest request) {
        if (request == null || request.importId()==null || request.schedule()==null
                || request.sheetPreviewToken()==null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"importId, schedule and sheetPreviewToken required");
        }
        var schedule=request.schedule();
        if (!Boolean.FALSE.equals(schedule.dryRun()) || !Boolean.TRUE.equals(schedule.confirmLive())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"Explicit dryRun=false and confirmLive=true required");
        }
        var fresh=preview(workspaceId,connectionId,request.importId(),operatorId,schedule);
        if (!MessageDigest.isEqual(fresh.previewToken().getBytes(StandardCharsets.UTF_8),
                request.sheetPreviewToken().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.CONFLICT,"Google Sheet changed since preview; preview again");
        }
        var live = new ExcelPublishingOrchestratorService.Request(
                schedule.startDate(),schedule.startTime(),schedule.intervalMinutes(),
                schedule.maxPostsPerDay(),schedule.timezone(),schedule.maxRows(),
                schedule.jobsPerRun(),false,true,fresh.orchestratorPreviewToken());
        var accepted=queue.enqueue(workspaceId,request.importId(),operatorId,live);
        db.update("""
            INSERT INTO google_sheet_queue_bindings(queue_id,connection_id,workspace_id,import_id)
            VALUES(?,?,?,?)
            """,accepted.queueId(),connectionId,workspaceId,request.importId());
        for (Chosen chosen: eligible(workspaceId,connectionId,request.importId(),schedule,
                schedule.maxRows()==null?5:schedule.maxRows())) {
            db.update("""
                INSERT INTO google_sheet_queue_rows(queue_id,source_row_number,post_id,row_hash)
                VALUES(?,?,?,?)
                """,accepted.queueId(),chosen.sheetRowNumber(),chosen.postId(),chosen.rowHash());
        }
        return accepted;
    }

    /** Called by the background worker BEFORE every LIVE chunk, if the queue came from Sheets. */
    public void verifyNext(UUID queueId, UUID workspaceId, UUID importId,
                           ExcelPublishingOrchestratorService.Request scheduled, int chunk) {
        List<UUID> connections=db.query("""
            SELECT connection_id FROM google_sheet_queue_bindings
            WHERE queue_id=? AND workspace_id=? AND import_id=?
            """,(rs,n)->rs.getObject(1,UUID.class),queueId,workspaceId,importId);
        if (connections.isEmpty()) return; // Preserve original Excel-only queue behavior.
        UUID connectionId=connections.getFirst();
        List<Chosen> next=eligible(workspaceId,connectionId,importId,scheduled,chunk);
        for (Chosen row:next) {
            Boolean ok=db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM google_sheet_queue_rows saved
                   JOIN google_sheet_staged_rows current
                     ON current.connection_id=? AND current.source_row_number=saved.source_row_number
                   WHERE saved.queue_id=? AND saved.source_row_number=? AND saved.post_id=?
                     AND saved.row_hash=current.row_hash)
                """,Boolean.class,connectionId,queueId,row.sheetRowNumber(),row.postId());
            if (!Boolean.TRUE.equals(ok)) throw new IllegalStateException(
                    "Sheet content changed since queue confirmation at row " + row.sheetRowNumber());
        }
    }
}
