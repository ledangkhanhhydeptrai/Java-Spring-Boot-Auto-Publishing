package com.socialhub.automation.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phase 2B deliberately DOES NOT call FanpageQueueProcessor.processBatch().
 * That API selects arbitrary jobs and cannot be safely used with an event job ID.
 * This consumer only validates and records observation of dispatch events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="app.automation.queue",name="observe-enabled",havingValue="true")
public class AutomationObserveConsumer {
    private final JdbcTemplate db;

    @RabbitListener(queues=AutomationQueueConfiguration.QUEUE)
    public void observe(String jobIdText) {
        UUID jobId = UUID.fromString(jobIdText);
        int updated = db.update("""
            UPDATE automation_job_outbox o
            SET state='OBSERVED', observed_at=NOW(),lease_until=NULL
            WHERE o.publishing_job_id=? AND o.state IN ('SENT','OBSERVED','SENDING')
              AND EXISTS (
                SELECT 1 FROM publishing_jobs j
                WHERE j.id=o.publishing_job_id AND j.status='PENDING'
              )
            """,jobId);
        if (updated>0) log.info("[AUTOMATION DRY RUN] Job event observed | jobId={}",jobId);
        else log.warn("[AUTOMATION DRY RUN] Ignored stale or unknown jobId={}",jobId);
    }
}
