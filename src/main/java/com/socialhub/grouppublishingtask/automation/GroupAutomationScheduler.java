package com.socialhub.grouppublishingtask.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Optional read-only monitor. Intentionally never approves, claims or publishes.
 */
@Component
public class GroupAutomationScheduler {
    private static final Logger log = LoggerFactory.getLogger(GroupAutomationScheduler.class);
    private final GroupTaskQueueService queue;
    private final boolean enabled;
    private UUID cursor;
    private static final int TARGETS_PER_TICK = 10;

    public GroupAutomationScheduler(GroupTaskQueueService queue,
                                    @Value("${app.group.automation.monitor-enabled:false}") boolean enabled) {
        this.queue = queue;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${app.group.automation.initial-delay-ms:30000}",
            fixedDelayString = "${app.group.automation.poll-ms:120000}")
    public void tick() {
        if (!enabled) return;
        try {
            var targets = queue.activeTargetsAfter(cursor, TARGETS_PER_TICK);
            if (targets.isEmpty() && cursor != null) {
                cursor = null;
                targets = queue.activeTargetsAfter(null, TARGETS_PER_TICK);
            }
            for (var target : targets) {
                cursor = target.groupId();
                try {
                    var counts = queue.counts(target.workspaceId(), target.groupId());
                    log.info("[GROUP AUTOMATION] Read-only: workspace={} group={} pending={} ready={} due={} published={} provider=UNCONFIGURED",
                            target.workspaceId(), target.groupId(), counts.pending(), counts.ready(), counts.dueReady(), counts.published());
                } catch (RuntimeException ex) {
                    log.warn("[GROUP AUTOMATION] Group monitor failed: {}", ex.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("[GROUP AUTOMATION] Monitor cycle stopped: {}", ex.getClass().getSimpleName());
        }
    }
}
