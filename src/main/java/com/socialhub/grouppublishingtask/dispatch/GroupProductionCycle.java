package com.socialhub.grouppublishingtask.dispatch;

import com.socialhub.grouppublishingtask.automation.GroupTaskQueueService;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;
import java.util.UUID;

/** One bounded scheduler cycle. Explicit wiring only, never a bean or @Scheduled method.
 * Before activation: approved provider, snapshot/rate-limit policy, isolated listener,
 * runtime DB/broker tests, and audit every task writer. No IDs stored in configuration. */
final class GroupProductionCycle {
    private final GroupTaskQueueService tasks;
    private final GroupDispatchClaimStore claims;
    private final GroupProductionOutboxRelay relay;
    private final GroupProductionExecutionStore execution;
    private final GroupProductionPublisher publisher;
    private final boolean enabled;
    private UUID cursor;
    GroupProductionCycle(JdbcTemplate db, GroupTaskQueueService tasks, GroupProductionOutboxRelay relay,
                         GroupProductionExecutionStore execution, GroupProductionPublisher publisher,
                         boolean explicitlyEnabled, boolean legacyPilotEnabled) {
        this.tasks=Objects.requireNonNull(tasks); this.claims=new GroupDispatchClaimStore(db);
        this.relay=Objects.requireNonNull(relay); this.execution=Objects.requireNonNull(execution);
        this.publisher=Objects.requireNonNull(publisher); this.enabled=explicitlyEnabled && !legacyPilotEnabled;
    }
    record Cycle(int groups, int staged, int confirmed, int review) {}
    synchronized Cycle runOnce(int groupLimit, int taskLimit, int maxBrokerAttempts) {
        if (groupLimit<1 || groupLimit>100 || taskLimit<1 || taskLimit>100 || maxBrokerAttempts<1 || maxBrokerAttempts>20)
            throw new IllegalArgumentException("Invalid cycle bounds");
        if (!enabled) return new Cycle(0,0,0,0);
        var targets=tasks.activeTargetsAfter(cursor,groupLimit);
        if (targets.isEmpty() && cursor!=null) { cursor=null; targets=tasks.activeTargetsAfter(null,groupLimit); }
        int groups=0, staged=0, confirmed=0, review=0;
        for (var target:targets) {
            if (Thread.currentThread().isInterrupted()) break;
            cursor=target.groupId(); groups++;
            UUID w=target.workspaceId(), g=target.groupId();
            review+=execution.reviewExpired(w,g,taskLimit);
            review+=execution.reviewDead(w,g,taskLimit);
            // Group authorization is checked again at the consumer before any remote side effect.
            if (!publisher.isGroupAuthorized(w,g)) continue;
            staged+=claims.claimAndStage(w,g,taskLimit).size();
            confirmed+=relay.relay(w,g,taskLimit,maxBrokerAttempts);
        }
        return new Cycle(groups,staged,confirmed,review);
    }
}
