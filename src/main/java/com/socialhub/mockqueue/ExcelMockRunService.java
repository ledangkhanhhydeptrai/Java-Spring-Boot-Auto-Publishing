package com.socialhub.mockqueue;

import com.socialhub.common.ApiException;
import com.socialhub.excelimport.repository.ExcelImportRepository;
import com.socialhub.excelimport.repository.ExcelImportRowRepository;
import com.socialhub.security.AccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MOCK-only Excel queue service. Persistent runs/jobs survive Spring restarts.
 * No external Meta API calls. Keep LIVE automation disabled in the MOCK profile.
 */
@Service
public class ExcelMockRunService {
    private static final Logger backendLog = LoggerFactory.getLogger(ExcelMockRunService.class);
    private static final Logger terminalLog = LoggerFactory.getLogger("socialhub.mock.terminal");
    public static final String MOCK_PAGE = "MOCK_PAGE_001";

    public record JobView(UUID id, int sourceRowNumber, UUID postId, String account,
                          String title, String content, String state) {
    }

    public record RunView(UUID runId, UUID importId, String page, int accountsConfigured,
                          int accountsUsed, int total, int completed, int failures,
                          String state, Instant startedAt, List<JobView> sampleJobs) {
    }

    private record EligibleRow(int row, UUID postId, String title, String content) {
    }

    private record JobData(UUID id, UUID runId, int row, UUID postId, String account,
                           String title, String content, String state) {
    }

    private record Progress(UUID runId, UUID importId, String account, int row, UUID postId,
                            String preview, int completed, int total, int accountsUsed,
                            boolean duplicate, boolean orphan, boolean complete) {
    }

    private final ExcelImportRepository imports;
    private final ExcelImportRowRepository rows;
    private final AccessService access;
    private final RabbitTemplate rabbit;
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int maxAccounts;
    private final int maxJobs;
    private final ObjectProvider<MockTerminalAutoLauncher> terminalLauncher;

    public ExcelMockRunService(ExcelImportRepository imports, ExcelImportRowRepository rows,
                               AccessService access, RabbitTemplate rabbit, Environment environment,
                               JdbcTemplate jdbc, TransactionTemplate tx,
                               ObjectProvider<MockTerminalAutoLauncher> terminalLauncher,
                               @Value("${socialhub.mock-queue.accounts:100}") int maxAccounts,
                               @Value("${socialhub.mock-excel.max-jobs:1000}") int maxJobs) {
        this.imports = imports;
        this.rows = rows;
        this.access = access;
        this.rabbit = rabbit;
        this.environment = environment;
        this.jdbc = jdbc;
        this.tx = tx;
        this.maxAccounts = maxAccounts;
        this.maxJobs = maxJobs;
        this.terminalLauncher = terminalLauncher;
    }

    private void requireSafeMode() {
        if (!environment.getProperty("socialhub.mock-queue.enabled", Boolean.class, false)
                || !"MOCK_ONLY".equals(environment.getProperty("socialhub.mock-queue.run-confirm", ""))
                || !"MOCK".equalsIgnoreCase(environment.getProperty("app.facebook.publishing-mode", "MOCK"))
                || environment.getProperty("app.automation.live.enabled", Boolean.class, false)
                || environment.getProperty("app.automation.live.facebook-worker-enabled", Boolean.class, false)
                || environment.getProperty("app.automation.live.threads-worker-enabled", Boolean.class, false)
                || environment.getProperty("app.automation.queue.enabled", Boolean.class, false)
                || environment.getProperty("app.automation.queue.dispatch-enabled", Boolean.class, false)
                || environment.getProperty("app.automation.queue.live-dispatch-enabled", Boolean.class, false)) {
            throw new ApiException(HttpStatus.CONFLICT, "MOCK-only run requires all LIVE publishers/dispatchers OFF");
        }
    }

    /**
     * Reuses an existing import's run instead of creating another 122 jobs on repeated clicks.
     */
    public synchronized RunView start(UUID workspaceId, UUID importId) {
        requireSafeMode();
        access.admin(workspaceId);
        if (maxAccounts < 1 || maxAccounts > 100 || maxJobs < 1 || maxJobs > 1000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Mock demo limits: 1..100 accounts, 1..1000 jobs");
        if (imports.findByIdAndWorkspaceId(importId, workspaceId).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "Excel import not found in this workspace");

        List<UUID> existing = jdbc.query(
                "SELECT id FROM mock_excel_runs WHERE workspace_id=? AND import_id=?",
                (rs, i) -> rs.getObject("id", UUID.class), workspaceId, importId);
        if (!existing.isEmpty()) return view(existing.get(0));

        // Run the JPA lookup inside a transaction so lazy Post relations are readable.
        List<EligibleRow> eligible = tx.execute(status -> rows.findByExcelImportIdOrderBySourceRowNumberAsc(importId)
                .stream()
                .filter(r -> "IMPORTED".equals(r.getProcessingStatus()))
                .filter(r -> r.getPost() != null && r.getPost().getContent() != null
                        && !r.getPost().getContent().isBlank())
                .map(r -> new EligibleRow(r.getSourceRowNumber(), r.getPost().getId(),
                        r.getPost().getTopic(), r.getPost().getContent()))
                .toList());
        if (eligible == null) throw new IllegalStateException("Cannot read imported Excel rows");
        if (eligible.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "No imported/unpublished Excel rows available for MOCK demo");
        if (eligible.size() > maxJobs)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Too many imported rows for demo; max " + maxJobs);

        UUID runId = UUID.randomUUID();
        List<UUID> jobIds = new ArrayList<>(eligible.size());
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                            INSERT INTO mock_excel_runs (id,workspace_id,import_id,page,accounts_configured,accounts_used,total)
                            VALUES (?,?,?,?,?,?,?)
                            """, runId, workspaceId, importId, MOCK_PAGE, maxAccounts,
                    Math.min(maxAccounts, eligible.size()), eligible.size());
            int i = 0;
            for (EligibleRow row : eligible) {
                UUID jobId = UUID.randomUUID();
                jobIds.add(jobId);
                String account = "A%04d".formatted((i++ % maxAccounts) + 1);
                jdbc.update("""
                        INSERT INTO mock_excel_jobs (id,run_id,source_row_number,post_id,account,title,content,state)
                        VALUES (?,?,?,?,?,?,?,'QUEUED')
                        """, jobId, runId, row.row(), row.postId(), account, row.title(), row.content());
            }
        });

        // Open the per-run PowerShell viewer AFTER the run has been persisted,
        // but BEFORE publishing any RabbitMQ messages. Terminal failures never block the run.
        terminalLauncher.ifAvailable(launcher -> launcher.openForExcelRun(runId, eligible.size()));

        backendLog.info("[MOCK ENGINE] Excel demo START | run={} import={} accountsUsed={} jobs={}",
                runId, importId, Math.min(maxAccounts, eligible.size()), eligible.size());
        terminalLog.info("[MOCK LOAD] SEED START | source=EXCEL run={} import={} accountsUsed={} jobs={} page={}",
                runId, importId, Math.min(maxAccounts, eligible.size()), eligible.size(), MOCK_PAGE);
        try {
            for (UUID jobId : jobIds) rabbit.convertAndSend(ExcelMockQueueConfig.QUEUE, runId + "|" + jobId);
        } catch (RuntimeException ex) {
            jdbc.update("UPDATE mock_excel_runs SET state='DISPATCH_FAILED' WHERE id=?", runId);
            backendLog.error("[MOCK ENGINE] Partial/failed queue dispatch | run={} (do not blindly retry start)", runId, ex);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "MOCK dispatch failed/partial; inspect RabbitMQ before retry");
        }
        terminalLog.info("[MOCK LOAD] SEED COMPLETE | source=EXCEL run={} queued={} noMetaCalls=true",
                runId, jobIds.size());
        return view(runId);
    }

    public RunView status(UUID workspaceId, UUID runId) {
        access.workspace(workspaceId);
        List<UUID> importsForRun = jdbc.query(
                "SELECT import_id FROM mock_excel_runs WHERE id=? AND workspace_id=?",
                (rs, i) -> rs.getObject(1, UUID.class), runId, workspaceId);
        if (importsForRun.isEmpty() || imports.findByIdAndWorkspaceId(importsForRun.get(0), workspaceId).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "MOCK run not found in workspace");
        return view(runId);
    }

    /**
     * Get the latest MOCK run for an Excel import.
     * Read-only: does not create jobs or publish messages.
     */
    public RunView latestForImport(UUID workspaceId, UUID importId) {

        access.workspace(workspaceId);

        if (imports.findByIdAndWorkspaceId(importId, workspaceId).isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "Excel import not found in this workspace"
            );
        }

        List<UUID> runIds = jdbc.query(
                """
                        SELECT id
                        FROM mock_excel_runs
                        WHERE workspace_id = ?
                          AND import_id = ?
                        ORDER BY started_at DESC, id DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                workspaceId,
                importId
        );

        if (runIds.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "No MOCK run found for this Excel import"
            );
        }

        return status(workspaceId, runIds.get(0));
    }

    private RunView view(UUID runId) {
        List<RunView> result = jdbc.query("""
                SELECT id,import_id,page,accounts_configured,accounts_used,total,completed,failures,state,started_at
                FROM mock_excel_runs WHERE id=?
                """, (rs, i) -> new RunView(
                rs.getObject("id", UUID.class), rs.getObject("import_id", UUID.class),
                rs.getString("page"), rs.getInt("accounts_configured"), rs.getInt("accounts_used"),
                rs.getInt("total"), rs.getInt("completed"), rs.getInt("failures"),
                rs.getString("state"), rs.getTimestamp("started_at").toInstant(), List.of()), runId);
        if (result.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "MOCK run not found");
        List<JobView> sample = jdbc.query("""
                SELECT id,source_row_number,post_id,account,title,content,state
                FROM mock_excel_jobs WHERE run_id=? ORDER BY source_row_number,account LIMIT 30
                """, (rs, i) -> new JobView(rs.getObject("id", UUID.class), rs.getInt("source_row_number"),
                rs.getObject("post_id", UUID.class), rs.getString("account"), rs.getString("title"),
                rs.getString("content"), rs.getString("state")), runId);
        RunView r = result.get(0);
        return new RunView(r.runId(), r.importId(), r.page(), r.accountsConfigured(), r.accountsUsed(),
                r.total(), r.completed(), r.failures(), r.state(), r.startedAt(), sample);
    }

    /**
     * Transactional and idempotent: the DB row, not a JVM map, owns each job state.
     * Old orphan messages are logged and archived, then ACKed so they cannot flood IntelliJ.
     */
    public void consume(String payload, long delayMs) {
        requireSafeMode();
        if (payload == null || !payload.matches("[a-fA-F0-9-]{36}\\|[a-fA-F0-9-]{36}")) {
            throw new IllegalArgumentException("Invalid Excel MOCK payload");
        }
        String[] ids = payload.split("\\|", 2);
        UUID runId = UUID.fromString(ids[0]);
        UUID jobId = UUID.fromString(ids[1]);
        Progress p = tx.execute(status -> consumeInTransaction(payload, runId, jobId, delayMs));
        if (p == null) throw new IllegalStateException("Unable to process MOCK queue message");
        if (p.orphan()) {
            backendLog.warn("[MOCK ENGINE] ORPHAN_MESSAGE_ARCHIVED | old RAM-only run={} job={} ; not publishable", runId, jobId);
            return;
        }
        if (p.duplicate()) {
            terminalLog.info("[MOCK WORKER] DUPLICATE_SKIPPED | source=EXCEL run={} job={}", runId, jobId);
            return;
        }
        terminalLog.info("[MOCK WORKER] PUBLISHED_SIMULATED | source=EXCEL run={} account={} row={} postId={} page={}",
                runId, p.account(), p.row(), p.postId(), MOCK_PAGE);
        if (p.completed() % 25 == 0 || p.complete()) {
            terminalLog.info("[MOCK LOAD] progress | source=EXCEL run={} completedUnique={}/{}",
                    p.runId(), p.completed(), p.total());
            backendLog.info("[MOCK ENGINE] Excel Worker running | completed={}/{} run={}",
                    p.completed(), p.total(), p.runId());
        }
        if (p.complete()) {
            terminalLog.info("[MOCK LOAD] COMPLETE | source=EXCEL run={} accountsUsed={} publishedSimulated={}/{} noMetaCalls=true",
                    p.runId(), p.accountsUsed(), p.completed(), p.total());
            backendLog.info("[MOCK ENGINE] AUTOMATION COMPLETED | source=EXCEL import={} run={} accountsUsed={} publishedSimulated={}/{}",
                    p.importId(), p.runId(), p.accountsUsed(), p.completed(), p.total());
        }
    }

    private Progress consumeInTransaction(String payload, UUID runId, UUID jobId, long delayMs) {
        List<JobData> jobRows = jdbc.query("""
                SELECT id,run_id,source_row_number,post_id,account,title,content,state
                FROM mock_excel_jobs WHERE id=? AND run_id=? FOR UPDATE
                """, (rs, i) -> new JobData(rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getInt("source_row_number"),
                rs.getObject("post_id", UUID.class), rs.getString("account"), rs.getString("title"),
                rs.getString("content"), rs.getString("state")), jobId, runId);
        if (jobRows.isEmpty()) {
            // Legacy messages refer only to UUIDs; their in-memory content is irrecoverable.
            jdbc.update("INSERT INTO mock_excel_orphan_messages(payload) VALUES (?) ON CONFLICT DO NOTHING", payload);
            return new Progress(runId, null, null, 0, null, null, 0, 0, 0, false, true, false);
        }
        JobData job = jobRows.get(0);
        if (!"QUEUED".equals(job.state())) {
            return new Progress(runId, null, job.account(), job.row(), job.postId(), null,
                    0, 0, 0, true, false, false);
        }
        terminalLog.info("[MOCK WORKER] START | source=EXCEL run={} account={} row={} postId={} page={}",
                runId, job.account(), job.row(), job.postId(), MOCK_PAGE);
        terminalLog.info("[MOCK WORKER] PUBLISHING | source=EXCEL run={} account={} row={} preview={} mode=SIMULATED",
                runId, job.account(), job.row(), job.content().replaceAll("[\\r\\n\\t]", " ")
                        .substring(0, Math.min(65, job.content().length())));
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("MOCK worker interrupted; RabbitMQ may retry", e);
            }
        }
        jdbc.update("UPDATE mock_excel_jobs SET state='PUBLISHED_SIMULATED',completed_at=now() WHERE id=?", jobId);
        // Atomic increment on the run row serializes parallel workers and prevents double completion.
        List<Progress> progress = jdbc.query("""
                UPDATE mock_excel_runs
                SET completed=completed+1,
                    state=CASE WHEN completed+1=total AND failures=0 THEN 'COMPLETED' ELSE state END
                WHERE id=?
                RETURNING import_id,accounts_used,completed,total,state
                """, (rs, i) -> new Progress(runId, rs.getObject("import_id", UUID.class),
                job.account(), job.row(), job.postId(), null, rs.getInt("completed"),
                rs.getInt("total"), rs.getInt("accounts_used"), false, false,
                "COMPLETED".equals(rs.getString("state")) && rs.getInt("completed") == rs.getInt("total")), runId);
        if (progress.isEmpty()) throw new IllegalStateException("MOCK run row disappeared");
        return progress.get(0);
    }
}
