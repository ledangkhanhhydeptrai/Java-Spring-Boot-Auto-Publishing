package com.socialhub.excelimport.service.implement;

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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in Threads planner for existing imported posts. Does not import new rows,
 * change source dates, send to Meta directly, or alter Facebook jobs.
 *
 * Designed for a single worker instance. The atomic claim prevents concurrent
 * instances from taking the same settings row at the same instant; the bridge
 * protects against duplicate Post+account job creation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThreadsAutoPlannerScheduler {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Value("${app.threads.auto-planner.enabled:false}")
    private boolean workerEnabled;

    private final JdbcTemplate db;
    private final ExcelThreadsBridgeService bridge;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private record Plan(UUID id, UUID workspaceId, UUID importId, UUID threadsAccountId,
                        UUID operatorId, int fromRow, int toRow, int maxRows,
                        int intervalMinutes, int startDelayMinutes) {}

    @Scheduled(fixedDelayString = "${app.threads.auto-planner.poll-ms:60000}")
    public void poll() {
        if (!workerEnabled || !busy.compareAndSet(false, true)) return;
        try {
            // One due setting per poll. Claim commits before the external work;
            // a crash cannot endlessly replay the same setting in a hot loop.
            List<Plan> claimed = db.query("""
                UPDATE threads_auto_planner_settings t
                   SET next_run_at = now() + make_interval(mins => t.poll_minutes),
                       last_run_at = now(), updated_at = now()
                 WHERE t.id = (
                   SELECT id FROM threads_auto_planner_settings
                   WHERE enabled = TRUE AND next_run_at <= now()
                   ORDER BY next_run_at, id
                   FOR UPDATE SKIP LOCKED LIMIT 1
                 )
                RETURNING t.id, t.workspace_id, t.import_id, t.threads_account_id,
                          t.operator_id, t.from_row, t.to_row, t.max_rows_per_run,
                          t.interval_minutes, t.start_delay_minutes
                """, (rs, i) -> new Plan(
                    rs.getObject("id", UUID.class),
                    rs.getObject("workspace_id", UUID.class),
                    rs.getObject("import_id", UUID.class),
                    rs.getObject("threads_account_id", UUID.class),
                    rs.getObject("operator_id", UUID.class),
                    rs.getInt("from_row"), rs.getInt("to_row"),
                    rs.getInt("max_rows_per_run"), rs.getInt("interval_minutes"),
                    rs.getInt("start_delay_minutes")));
            if (!claimed.isEmpty()) runOne(claimed.getFirst());
        } catch (RuntimeException ex) {
            log.error("[THREADS AUTO] Poll failed", ex);
        } finally {
            busy.set(false);
        }
    }

    private void runOne(Plan cfg) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext scoped = SecurityContextHolder.createEmptyContext();
        scoped.setAuthentication(new UsernamePasswordAuthenticationToken(
                cfg.operatorId().toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(scoped);
        try {
            // Require that operator STILL has OWNER/ADMIN rights. The bridge
            // validates membership, import/account linkage and CONNECTED status.
            Boolean isAdmin = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM workspace_members
                              WHERE workspace_id=? AND user_id=? AND role IN ('OWNER','ADMIN'))
                """, Boolean.class, cfg.workspaceId(), cfg.operatorId());
            if (!Boolean.TRUE.equals(isAdmin)) {
                throw new IllegalStateException("Configured operator no longer owns/administers workspace");
            }
            // Recheck enabled flag since the claim (an operator may disable it).
            Boolean enabled = db.queryForObject(
                    "SELECT enabled FROM threads_auto_planner_settings WHERE id=?",
                    Boolean.class, cfg.id());
            if (!Boolean.TRUE.equals(enabled)) return;

            // A fresh future starting slot each run. Round up to the next minute.
            ZonedDateTime start = ZonedDateTime.now(ZONE)
                    .plusMinutes(cfg.startDelayMinutes())
                    .withSecond(0).withNano(0).plusMinutes(1);

            var previewRequest = new ExcelThreadsBridgeService.Request(
                    cfg.threadsAccountId(), start.toLocalDate(), start.toLocalTime(),
                    cfg.intervalMinutes(), cfg.maxRows(), true, false, null,
                    cfg.fromRow(), cfg.toRow());
            var preview = bridge.plan(cfg.workspaceId(), cfg.importId(), previewRequest);

            // Do not silently create partial batches with blocked entries.
            if (preview.blocked() > 0 || !preview.errors().isEmpty()) {
                throw new IllegalStateException("Preview blocked=" + preview.blocked()
                        + " errors=" + preview.errors());
            }
            if (preview.eligible() == 0) {
                recordSuccess(cfg.id(), 0);
                return;
            }
            var liveRequest = new ExcelThreadsBridgeService.Request(
                    cfg.threadsAccountId(), start.toLocalDate(), start.toLocalTime(),
                    cfg.intervalMinutes(), cfg.maxRows(), false, true,
                    preview.previewToken(), cfg.fromRow(), cfg.toRow());
            var live = bridge.plan(cfg.workspaceId(), cfg.importId(), liveRequest);
            if (live.blocked() > 0 || !live.errors().isEmpty()) {
                throw new IllegalStateException("Partial live creation: created=" + live.created()
                        + ", blocked=" + live.blocked() + ", errors=" + live.errors()
                        + ". Reconcile publishing_jobs before retry");
            }
            recordSuccess(cfg.id(), live.created());
            log.info("[THREADS AUTO] import={} account={} created={}",
                    cfg.importId(), cfg.threadsAccountId(), live.created());
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            db.update("""
                 UPDATE threads_auto_planner_settings
                    SET last_error=?, updated_at=now()
                  WHERE id=?
                 """, reason.substring(0, Math.min(1500, reason.length())), cfg.id());
            log.warn("[THREADS AUTO] config={} requires review: {}", cfg.id(), reason);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private void recordSuccess(UUID id, int created) {
        db.update("""
            UPDATE threads_auto_planner_settings
               SET last_error=NULL, last_created=?, updated_at=now()
             WHERE id=?
            """, created, id);
    }
}
