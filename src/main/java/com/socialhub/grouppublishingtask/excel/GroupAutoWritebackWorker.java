package com.socialhub.grouppublishingtask.excel;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only copies recorded PUBLISHED group links. Never approves, claims or publishes a task. */
@Slf4j
@Component
public class GroupAutoWritebackWorker {
    private final GroupSheetQuickPlanController planner;
    private final boolean enabled;
    private final UUID connectionId;
    private final UUID groupId;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "group-writeback-io");
        t.setDaemon(true);
        return t;
    });

    public GroupAutoWritebackWorker(GroupSheetQuickPlanController planner,
            @Value("${app.group.writeback.enabled:false}") boolean enabled,
            @Value("${app.group.writeback.connection-id:}") String connection,
            @Value("${app.group.writeback.group-id:}") String group) {
        this.planner = planner;
        this.enabled = enabled;
        this.connectionId = parse(connection);
        this.groupId = parse(group);
        if (enabled && (connectionId == null || groupId == null))
            log.warn("[GROUP WRITEBACK] Disabled: both configured UUIDs must be valid");
    }

    private static UUID parse(String raw) {
        try { return UUID.fromString(raw.strip()); }
        catch (RuntimeException invalid) { return null; }
    }

    // Dispatch only: Google I/O never blocks the shared Spring publishing scheduler.
    @Scheduled(initialDelayString="${app.group.writeback.initial-delay-ms:30000}",
               fixedDelayString="${app.group.writeback.poll-ms:120000}")
    public void tick() {
        if (!enabled || connectionId == null || groupId == null || io.isShutdown()
                || !running.compareAndSet(false, true)) return;
        try {
            io.execute(() -> {
                try {
                    var report = planner.runConfiguredWriteback(connectionId, groupId);
                    if (report != null && (report.written() > 0 || report.blocked() > 0))
                        log.info("[GROUP WRITEBACK] candidates={} written={} synced={} blocked={}",
                                report.candidates(), report.written(), report.alreadySynced(), report.blocked());
                } catch (RuntimeException failure) {
                    log.warn("[GROUP WRITEBACK] Stopped this cycle: {}", failure.getClass().getSimpleName());
                } finally { running.set(false); }
            });
        } catch (RuntimeException rejected) { running.set(false); }
    }

    @PreDestroy
    public void stop() { io.shutdownNow(); }
}
