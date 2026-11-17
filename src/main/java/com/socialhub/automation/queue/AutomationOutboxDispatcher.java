package com.socialhub.automation.queue;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Sends due-job Outbox events to RabbitMQ, with correlated broker confirms and returns.
 * OBSERVE and LIVE routing remain strictly separate.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.runtime", name = "role", havingValue = "api", matchIfMissing = true)
@RequiredArgsConstructor
public class AutomationOutboxDispatcher {

    private record Event(UUID id, UUID jobId, String platform) {
    }

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RabbitTemplate rabbit;
    private final AutomationOutboxService outbox;

    private RabbitTemplate confirmedRabbit;

    @Value("${app.automation.queue.enabled:false}")
    private boolean enabled;

    @Value("${app.automation.queue.dispatch-enabled:false}")
    private boolean dispatchEnabled;

    @Value("${app.automation.queue.live-dispatch-enabled:false}")
    private boolean liveDispatchEnabled;

    @Value("${app.automation.queue.observe-enabled:false}")
    private boolean observeEnabled;

    @Value("${app.automation.live.enabled:false}")
    private boolean liveEnabled;

    @Value("${app.publisher-enabled:false}")
    private boolean legacyFacebookEnabled;

    @Value("${app.threads.publisher-enabled:false}")
    private boolean legacyThreadsEnabled;

    @Value("${app.automation.queue.confirm-timeout-ms:15000}")
    private long confirmTimeoutMs;

    @PostConstruct
    void initConfirmedTemplate() {
        ConnectionFactory factory = rabbit.getConnectionFactory();
        if (factory == null) {
            throw new IllegalStateException("RabbitTemplate has no ConnectionFactory");
        }
        // Dedicated template: do not change the application's other RabbitTemplate users.
        confirmedRabbit = new RabbitTemplate(factory);
        confirmedRabbit.setMandatory(true);
    }

    @Scheduled(fixedDelayString = "${app.automation.queue.poll-ms:15000}")
    public void tick() {
        if (!enabled || !dispatchEnabled) return;

        if (liveDispatchEnabled
                && (observeEnabled || !liveEnabled || legacyFacebookEnabled || legacyThreadsEnabled)) {
            log.error("[AUTOMATION] LIVE dispatch blocked by conflicting configuration");
            return;
        }
        if (!liveDispatchEnabled && !observeEnabled) return;
        if (confirmTimeoutMs < 1000 || confirmTimeoutMs > 60000) {
            log.error("[AUTOMATION] Invalid confirm timeout (must be 1000..60000ms)");
            return;
        }

        try {
            // A previous LIVE send with no confirmed result cannot be blindly replayed.
            if (liveDispatchEnabled) reviewExpiredLiveSending();

            outbox.stageDueJobs();
            for (int i = 0; i < 25; i++) {
                Event event = claimNext();
                if (event == null) break;
                try {
                    sendAndConfirm(event);
                    int changed = db.update("""
                            UPDATE automation_job_outbox
                            SET state='SENT', sent_at=NOW(), lease_until=NULL, last_error=NULL
                            WHERE id=? AND state='SENDING'
                            """, event.id());
                    // The consumer can finish first. Do not overwrite OBSERVED / EXECUTED / REVIEW.
                    if (changed == 0) {
                        log.debug("[AUTOMATION] Outbox event={} was already finalized by consumer", event.id());
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    deliveryNotConfirmed(event, "Interrupted");
                    return;
                } catch (Exception ex) {
                    // Never persist raw exception bodies: connection logs might contain secrets.
                    log.warn("[AUTOMATION] Event={} delivery unconfirmed ({})",
                            event.id(), ex.getClass().getSimpleName());
                    deliveryNotConfirmed(event, ex.getClass().getSimpleName());
                    return;
                }
            }
        } catch (Exception ex) {
            log.error("[AUTOMATION] Outbox dispatcher failed", ex);
        }
    }

    private void sendAndConfirm(Event event) throws Exception {
        String routingKey = liveDispatchEnabled
                ? switch (event.platform()) {
            case "FACEBOOK_PAGE" -> AutomationQueueConfiguration.FACEBOOK_LIVE_KEY;
            case "THREADS" -> AutomationQueueConfiguration.THREADS_LIVE_KEY;
            default -> throw new IllegalStateException("Unsupported outbox platform");
        }
                : AutomationQueueConfiguration.KEY;

        CorrelationData correlation = new CorrelationData(event.id().toString());
        CorrelationData.Confirm confirm =
                correlation.getFuture().get(
                        confirmTimeoutMs,
                        TimeUnit.MILLISECONDS
                );

        if (!confirm.ack()) {
            throw new IllegalStateException(
                    "RabbitMQ publisher NACK: " + confirm.reason()
            );
        }

        if (correlation.getReturned() != null) {
            throw new IllegalStateException(
                    "RabbitMQ routing failed (unroutable)"
            );
        }
    }

    private void deliveryNotConfirmed(Event event, String reason) {
        if (liveDispatchEnabled) {
            // The broker may have accepted the message before the timeout/crash.
            // Do not blindly re-send LIVE messages without human reconciliation.
            db.update("""
                    UPDATE automation_job_outbox
                    SET state='REVIEW', lease_until=NULL,
                        last_error=?
                    WHERE id=? AND state='SENDING'
                    """, "RabbitMQ delivery uncertain: " + reason, event.id());
        } else {
            // OBSERVE never calls Meta, so replaying an event is safe.
            db.update("""
                    UPDATE automation_job_outbox
                    SET state='PENDING', lease_until=NULL,
                        next_attempt_at=NOW()+INTERVAL '30 seconds', last_error=?
                    WHERE id=? AND state='SENDING'
                    """, reason, event.id());
        }
    }

    private void reviewExpiredLiveSending() {
        // Crash recovery: never replay an uncertain LIVE delivery.
        int count = db.update("""
                UPDATE automation_job_outbox
                SET state='REVIEW', lease_until=NULL,
                    last_error='LIVE delivery lease expired; verify broker and job outcome'
                WHERE state='SENDING' AND lease_until < NOW()
                """);
        if (count > 0) log.warn("[AUTOMATION] {} expired LIVE outbox sends require review", count);
    }

    private Event claimNext() {
        return tx.execute(status -> {
            List<Event> items = db.query("""
                    WITH chosen AS (
                        SELECT id FROM automation_job_outbox
                        WHERE (state='PENDING' AND next_attempt_at<=NOW())
                           OR (CAST(? AS BOOLEAN)=TRUE AND state='SENDING' AND lease_until<NOW())
                        ORDER BY next_attempt_at, created_at
                        LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE automation_job_outbox o
                    SET state='SENDING', delivery_attempts=delivery_attempts+1,
                        lease_until=NOW()+INTERVAL '2 minutes'
                    FROM chosen WHERE o.id=chosen.id
                    RETURNING o.id,o.publishing_job_id,o.platform
                    """, (rs, i) -> new Event(rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class), rs.getString(3)), !liveDispatchEnabled);
            return items.isEmpty() ? null : items.getFirst();
        });
    }
}
