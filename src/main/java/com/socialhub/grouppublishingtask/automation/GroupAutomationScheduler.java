package com.socialhub.grouppublishingtask.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Optional read-only monitor. Intentionally never approves, claims or publishes. */
@Component
public class GroupAutomationScheduler {
    private static final Logger log=LoggerFactory.getLogger(GroupAutomationScheduler.class);
    private final GroupTaskQueueService queue;
    private final boolean enabled;
    private final UUID workspaceId;
    private final UUID groupId;

    public GroupAutomationScheduler(GroupTaskQueueService queue,
            @Value("${app.group.automation.monitor-enabled:false}") boolean enabled,
            @Value("${app.group.automation.workspace-id:}") String workspaceId,
            @Value("${app.group.automation.group-id:}") String groupId) {
        this.queue=queue; this.enabled=enabled;
        this.workspaceId=parse(workspaceId); this.groupId=parse(groupId);
        if (enabled && (this.workspaceId==null || this.groupId==null))
            log.warn("[GROUP AUTOMATION] Monitor disabled: explicit valid workspace/group UUIDs required");
    }

    @Scheduled(initialDelayString="${app.group.automation.initial-delay-ms:30000}",
               fixedDelayString="${app.group.automation.poll-ms:120000}")
    public void tick() {
        if (!enabled || workspaceId==null || groupId==null) return;
        try {
            var counts=queue.counts(workspaceId,groupId);
            log.info("[GROUP AUTOMATION] Read-only: pending={} ready={} due={} published={} provider=UNCONFIGURED",
                    counts.pending(),counts.ready(),counts.dueReady(),counts.published());
        } catch (RuntimeException ex) {
            log.warn("[GROUP AUTOMATION] Monitor cycle stopped: {}",ex.getClass().getSimpleName());
        }
    }

    private static UUID parse(String raw) {
        try { return UUID.fromString(raw.strip()); }
        catch (RuntimeException ex) { return null; }
    }
}
