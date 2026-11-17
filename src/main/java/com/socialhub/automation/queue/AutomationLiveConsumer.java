package com.socialhub.automation.queue;

import com.socialhub.automation.live.AutomationExactJobExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Exact-job LIVE queue consumers. Never invoke a batch publisher.
 * Uncertain execution outcomes require manual review, not an automatic Meta retry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.automation.live", name = "enabled", havingValue = "true")
public class AutomationLiveConsumer {

    private final AutomationExactJobExecutor executor;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    @Component
    @RequiredArgsConstructor
    @ConditionalOnProperty(prefix = "app.automation.live", name = "facebook-worker-enabled", havingValue = "true")
    public static class FacebookWorker {
        private final AutomationLiveConsumer consumer;

        @RabbitListener(queues = AutomationQueueConfiguration.FACEBOOK_LIVE_QUEUE)
        public void receive(String jobIdText) {
            consumer.consume(jobIdText, "FACEBOOK_PAGE");
        }
    }

    @Component
    @RequiredArgsConstructor
    @ConditionalOnProperty(prefix = "app.automation.live", name = "threads-worker-enabled", havingValue = "true")
    public static class ThreadsWorker {
        private final AutomationLiveConsumer consumer;

        @RabbitListener(queues = AutomationQueueConfiguration.THREADS_LIVE_QUEUE)
        public void receive(String jobIdText) {
            consumer.consume(jobIdText, "THREADS");
        }
    }

    void consume(String jobIdText, String expectedPlatform) {
        final UUID jobId;
        try {
            jobId = UUID.fromString(jobIdText);
        } catch (RuntimeException ex) {
            log.warn("[AUTOMATION LIVE] Invalid job ID in message");
            return;
        }

        // Outbox and platform must agree; no arbitrary job selection.
        List<String> statuses = db.query("""
                SELECT j.status
                FROM automation_job_outbox o
                JOIN publishing_jobs j ON j.id=o.publishing_job_id
                JOIN social_accounts a ON a.id=j.social_account_id
                WHERE o.publishing_job_id=?
                  AND o.platform=? AND a.platform=?
                  AND o.state IN ('SENDING', 'SENT')
                """, (rs, i) -> rs.getString(1), jobId, expectedPlatform, expectedPlatform);

        if (statuses.isEmpty()) {
            log.warn("[AUTOMATION LIVE] Ignored unauthorized/stale event job={} platform={}",
                    jobId, expectedPlatform);
            return;
        }

        // Redelivery while the first worker is still processing must NOT corrupt its state.
        if ("PROCESSING".equals(statuses.getFirst())) {
            log.info("[AUTOMATION LIVE] Duplicate delivery while job={} is processing; ignored", jobId);
            return;
        }

        // Reconcile terminal jobs from prior deliveries instead of republishing.
        if (!"PENDING".equals(statuses.getFirst())) {
            reconcileOutcome(jobId);
            return;
        }

        try {
            executor.execute(jobId);
        } catch (Exception ex) {
            log.error("[AUTOMATION LIVE] Execution failed for job={} ({})",
                    jobId, ex.getClass().getSimpleName());
        }
        // Re-read PostgreSQL. Executor may return without throwing on FAILED / NEEDS_REVIEW.
        reconcileOutcome(jobId);
    }

    private void reconcileOutcome(UUID jobId) {
        tx.executeWithoutResult(transaction -> {
            List<String> statuses = db.query("""
                    SELECT status FROM publishing_jobs WHERE id=? FOR UPDATE
                    """, (rs, i) -> rs.getString(1), jobId);
            String status = statuses.isEmpty() ? "MISSING" : statuses.getFirst();

            if ("PUBLISHED".equals(status)) {
                db.update("""
                        UPDATE automation_job_outbox
                        SET state='EXECUTED', observed_at=NOW(),
                            lease_until=NULL,last_error=NULL
                        WHERE publishing_job_id=? AND state IN ('SENDING','SENT','REVIEW')
                        """, jobId);
                log.info("[AUTOMATION LIVE] Published job={} confirmed from database", jobId);
                return;
            }

            if ("PROCESSING".equals(status)) {
                // Might be a duplicate delivery or a worker that died mid-request.
                // The lease-expiration reconciler below resolves abandoned attempts.
                log.warn("[AUTOMATION LIVE] Job={} still PROCESSING; no retry and no forced state change", jobId);
                return;
            }

            // Guard against re-delivery after an exception before claim.
            // Conservatively block automatic execution of an unclaimed PENDING job.
            if ("PENDING".equals(status)) {
                db.update("""
                        UPDATE publishing_jobs
                        SET status='NEEDS_REVIEW',
                            last_error='LIVE execution did not claim the job; manual review required',
                            locked_until=NULL, next_retry_at=NULL
                        WHERE id=? AND status='PENDING'
                        """, jobId);
            }

            db.update("""
                    UPDATE automation_job_outbox
                    SET state='REVIEW', observed_at=NOW(), lease_until=NULL,
                        last_error=?
                    WHERE publishing_job_id=? AND state IN ('SENDING','SENT')
                    """, "Job outcome: " + status, jobId);
            log.warn("[AUTOMATION LIVE] Job={} status={} requires review", jobId, status);
        });
    }

    /**
     * The legacy scheduler is disabled in Phase 2C, so it cannot recover old
     * PROCESSING jobs. A LIVE-specific lease watchdog must do that safely.
     * Never retry the request: a timed-out request may have succeeded at Meta.
     */
    @Scheduled(fixedDelayString = "${app.automation.live.recovery-poll-ms:60000}")
    public void reconcileAbandonedLiveJobs() {
        tx.executeWithoutResult(transaction -> {
            List<UUID> expired = db.query("""
                    SELECT j.id
                    FROM publishing_jobs j
                    JOIN automation_job_outbox o ON o.publishing_job_id=j.id
                    WHERE j.status='PROCESSING'
                      AND j.locked_until < NOW() - INTERVAL '5 minutes'
                      AND o.state IN ('SENDING','SENT','REVIEW')
                    ORDER BY j.locked_until
                    LIMIT 50 FOR UPDATE OF j SKIP LOCKED
                    """, (rs, i) -> rs.getObject(1, UUID.class));

            for (UUID jobId : expired) {
                db.update("""
                        UPDATE publishing_jobs
                        SET status='NEEDS_REVIEW', locked_until=NULL, next_retry_at=NULL,
                            last_error='LIVE worker lease expired; publication outcome unknown'
                        WHERE id=? AND status='PROCESSING'
                        """, jobId);
                db.update("""
                        UPDATE publishing_attempts
                        SET status='UNKNOWN', finished_at=NOW(),
                            error_message='LIVE worker lease expired; verify on Meta before retry'
                        WHERE publishing_job_id=? AND status='PROCESSING'
                        """, jobId);
                db.update("""
                        UPDATE automation_job_outbox
                        SET state='REVIEW', observed_at=NOW(), lease_until=NULL,
                            last_error='LIVE worker lease expired; verify on Meta before retry'
                        WHERE publishing_job_id=? AND state IN ('SENDING','SENT','REVIEW')
                        """, jobId);
                log.warn("[AUTOMATION LIVE] Abandoned job={} moved to NEEDS_REVIEW", jobId);
            }
        });
    }
}
