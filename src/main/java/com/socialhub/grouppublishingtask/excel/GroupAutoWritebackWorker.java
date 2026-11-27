package com.socialhub.grouppublishingtask.excel;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads only explicitly enabled DB bindings; writes recorded PUBLISHED links only.
 */
@Slf4j
@Component
public class GroupAutoWritebackWorker {
    private final GroupWritebackTargetService targets;
    private final boolean enabled;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();
    private UUID cursor; // Used only by the single I/O thread. Restart safely begins at first target.
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "group-writeback-io");
        t.setDaemon(true);
        return t;
    });

    public GroupAutoWritebackWorker(GroupWritebackTargetService targets,
                                    @Value("${app.group.writeback.enabled:false}") boolean enabled,
                                    @Value("${app.group.writeback.targets-per-cycle:5}") int batchSize) {
        this.targets = targets;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 20));
    }

    @Scheduled(initialDelayString = "${app.group.writeback.initial-delay-ms:30000}",
            fixedDelayString = "${app.group.writeback.poll-ms:120000}")
    public void tick() {
        if (!enabled || io.isShutdown() || !running.compareAndSet(false, true)) return;
        try {
            io.execute(() -> {
                try {
                    List<UUID> batch = targets.nextTargets(cursor, batchSize);
                    if (batch.isEmpty() && cursor != null) {
                        cursor = null;
                        batch = targets.nextTargets(null, batchSize);
                    }
                    for (UUID connectionId : batch) {
                        if (Thread.currentThread().isInterrupted()) break;
                        cursor = connectionId;
                        try {
                            var report = targets.run(connectionId);
                            if (report != null && (report.written() > 0 || report.blocked() > 0))
                                log.info("[GROUP WRITEBACK] connection={} candidates={} written={} synced={} blocked={}",
                                        connectionId, report.candidates(), report.written(), report.alreadySynced(), report.blocked());
                        } catch (RuntimeException failure) {
                            log.warn("[GROUP WRITEBACK] Target skipped: connection={} error={}", connectionId, failure.getClass().getSimpleName());
                        }
                    }
                    if (batch.size() < batchSize) cursor = null;
                } catch (RuntimeException failure) {
                    log.warn("[GROUP WRITEBACK] Scan failed: {}", failure.getClass().getSimpleName());
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            running.set(false);
        }
    }

    @PreDestroy
    public void stop() {
        io.shutdownNow();
    }
}
