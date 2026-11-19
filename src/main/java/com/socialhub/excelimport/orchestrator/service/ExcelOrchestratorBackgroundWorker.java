package com.socialhub.excelimport.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Safe MVP: one persisted request is divided into at most ten sub-batches of
 * five jobs. On unexpected process death, a RUNNING request is NEVER replayed
 * automatically: operators must reconcile the database before any new run.
 * For multi-instance production deployment, introduce a lease/heartbeat and
 * explicit reconciliation before enabling automatic recovery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExcelOrchestratorBackgroundWorker {
    @Value("${APP_EXCEL_ORCHESTRATOR_WORKER_ENABLED:false}")
    private boolean workerEnabled;

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final ExcelPublishingOrchestratorService orchestrator;
    private final AtomicBoolean executing = new AtomicBoolean(false);

    private record Work(UUID id, UUID workspaceId, UUID importId, UUID operatorId,
                        String requestJson, int requestedRows, int processedRows) {}

    @Scheduled(fixedDelayString="${APP_EXCEL_ORCHESTRATOR_POLL_MS:5000}")
    public void poll() {
        if (!workerEnabled || !executing.compareAndSet(false, true)) return;
        try {
            // Atomic UPDATE ... RETURNING: two processes cannot claim the same QUEUED row.
            List<Work> claimed = db.query("""
                    UPDATE excel_orchestrator_queue q
                       SET status='RUNNING', started_at=NOW(), updated_at=NOW()
                     WHERE id=(SELECT id FROM excel_orchestrator_queue
                               WHERE status='QUEUED'
                               ORDER BY created_at, id
                               FOR UPDATE SKIP LOCKED LIMIT 1)
                    RETURNING q.id,q.workspace_id,q.import_id,q.operator_id,
                              q.request_payload::text AS payload,q.requested_rows,q.processed_rows
                    """, (rs,n) -> new Work(rs.getObject("id",UUID.class),
                        rs.getObject("workspace_id",UUID.class),
                        rs.getObject("import_id",UUID.class),
                        rs.getObject("operator_id",UUID.class),rs.getString("payload"),
                        rs.getInt("requested_rows"),rs.getInt("processed_rows")));
            if (!claimed.isEmpty()) process(claimed.getFirst());
        } catch (Exception ex) {
            log.error("[ORCHESTRATOR QUEUE] Poll failed",ex);
        } finally {
            executing.set(false);
        }
    }

    private void process(Work work) {
        // The user's authorization is validated again against the database.
        // The principal is scoped to this worker thread and cleared afterwards.
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext scoped = SecurityContextHolder.createEmptyContext();
        scoped.setAuthentication(new UsernamePasswordAuthenticationToken(
                work.operatorId().toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(scoped);
        try {
            var payload = mapper.readTree(work.requestJson());
            var original = new ExcelPublishingOrchestratorService.Request(
                    LocalDate.parse(payload.get("startDate").asText()),
                    LocalTime.parse(payload.get("startTime").asText()),
                    payload.hasNonNull("intervalMinutes") ? payload.get("intervalMinutes").asInt() : null,
                    payload.hasNonNull("maxPostsPerDay") ? payload.get("maxPostsPerDay").asInt() : null,
                    payload.hasNonNull("timezone") ? payload.get("timezone").asText() : null,
                    work.requestedRows(),
                    payload.hasNonNull("jobsPerRun") ? payload.get("jobsPerRun").asInt() : 5,
                    false,true,null);
            int processed = work.processedRows();
            int perRun = original.jobsPerRun() == null ? 5 : original.jobsPerRun();
            if (perRun < 1 || perRun > 5) throw new IllegalStateException("Invalid stored jobsPerRun");
            while (processed < work.requestedRows()) {
                // Reject revoked admin permissions even for a previously accepted request.
                Boolean manager = db.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM workspace_members
                         WHERE workspace_id=? AND user_id=? AND role IN ('OWNER','ADMIN'))
                        """, Boolean.class,work.workspaceId(),work.operatorId());
                if (!Boolean.TRUE.equals(manager)) throw new IllegalStateException("Operator no longer has admin rights");

                int chunk = Math.min(perRun, work.requestedRows()-processed);
                var previewRequest = new ExcelPublishingOrchestratorService.Request(
                        original.startDate(),original.startTime(),original.intervalMinutes(),
                        original.maxPostsPerDay(),original.timezone(),chunk,perRun,true,false,null);
                var preview = orchestrator.execute(work.workspaceId(),work.importId(),
                        work.operatorId(), previewRequest);
                if (preview.selectedRows()!=chunk || preview.blocked()>0) {
                    throw new IllegalStateException("Batch preview no longer has " + chunk
                            + " clean eligible rows; review the import and scheduled jobs");
                }
                var liveRequest = new ExcelPublishingOrchestratorService.Request(
                        original.startDate(),original.startTime(),original.intervalMinutes(),
                        original.maxPostsPerDay(),original.timezone(),chunk,perRun,false,true,
                        preview.previewToken());
                var result = orchestrator.execute(work.workspaceId(),work.importId(),
                        work.operatorId(),liveRequest);
                // Persist progress and newly-created Bulk Run IDs BEFORE another batch.
                // A partial result MUST be persisted, but never retried automatically.
                String resultJson = mapper.writeValueAsString(result);
                int created = result.createdJobs();
                transactions.executeWithoutResult(tx -> {
                    for (var run : result.runs()) {
                        db.update("""
                                INSERT INTO excel_orchestrator_bulk_runs(queue_id,bulk_run_id)
                                VALUES (?,?) ON CONFLICT DO NOTHING
                                """,work.id(),run.bulkRunId());
                    }
                    db.update("""
                            UPDATE excel_orchestrator_queue SET
                                processed_rows=processed_rows+?, created_jobs=created_jobs+?,
                                prepared_jobs=prepared_jobs+?, started_runs=started_runs+?,
                                last_result=?::jsonb,updated_at=NOW()
                            WHERE id=? AND status='RUNNING'
                            """,created,created,result.preparedJobs(),
                            result.startedRuns(),resultJson,work.id());
                });
                processed += created;
                if (!"RUNNING".equals(result.status()) || created!=chunk
                        || result.preparedJobs()!=chunk || result.startedRuns()!=1) {
                    throw new IllegalStateException("A batch ended partially: " + result.status()
                            + "; " + result.errors() + ". Inspect Bulk Runs before trying again");
                }
            }
            db.update("""
                    UPDATE excel_orchestrator_queue
                    SET status='DISPATCHED',finished_at=NOW(),updated_at=NOW()
                    WHERE id=? AND status='RUNNING'
                    """,work.id());
            log.info("[ORCHESTRATOR QUEUE] Prepared {} LIVE jobs, run={}",processed,work.id());
        } catch (Exception ex) {
            log.error("[ORCHESTRATOR QUEUE] Review needed run={}",work.id(),ex);
            String reason = ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();
            db.update("""
                    UPDATE excel_orchestrator_queue
                    SET status='REVIEW_REQUIRED',last_error=?,finished_at=NOW(),updated_at=NOW()
                    WHERE id=? AND status='RUNNING'
                    """,reason.substring(0,Math.min(reason.length(),1800)),work.id());
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
