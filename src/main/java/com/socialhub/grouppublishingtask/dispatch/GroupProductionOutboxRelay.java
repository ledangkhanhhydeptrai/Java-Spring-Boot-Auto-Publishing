package com.socialhub.grouppublishingtask.dispatch;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Callable relay, deliberately no scheduled method/bean. Broker retry only, not post retry.
 */
final class GroupProductionOutboxRelay {
    private final GroupOutboxLeaseStore leases;
    private final GroupProductionBroker broker;

    GroupProductionOutboxRelay(JdbcTemplate db, GroupProductionBroker broker) {
        this.leases = new GroupOutboxLeaseStore(db);
        this.broker = broker;
    }

    // One lease at a time so a batch cannot outlive its lease while waiting on earlier confirms.
    int relay(UUID workspaceId, UUID groupId, int limit, int maxAttempts) {
        return relay(workspaceId, groupId, limit, maxAttempts, null);
    }

    int relay(UUID workspaceId, UUID groupId, int limit, int maxAttempts, UUID exactTask) {
        if (limit < 1 || limit > 100 || maxAttempts < 1 || maxAttempts > 20)
            throw new IllegalArgumentException("Invalid relay bounds");
        leases.expireExhausted(workspaceId, groupId, maxAttempts, limit, exactTask);
        int confirmed = 0;
        for (int i = 0; i < limit && !Thread.currentThread().isInterrupted(); i++) {
            var batch = leases.acquire(workspaceId, groupId, 1, 60, maxAttempts, exactTask);
            if (batch.isEmpty()) break;
            var lease = batch.getFirst();
            var delivery = broker.send(new GroupDispatchEnvelope(lease.eventId(), lease.taskId(), workspaceId, groupId));
            if (delivery == GroupProductionBroker.Delivery.CONFIRMED) {
                if (leases.confirm(lease)) confirmed++;
            } else {
                leases.retryOrDead(lease, maxAttempts, GroupDispatchEnvelope.retryDelaySeconds(lease.attempt()), "BROKER_" + delivery.name());
            }
        }
        return confirmed;
    }
}
