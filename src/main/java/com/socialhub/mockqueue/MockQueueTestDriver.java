package com.socialhub.mockqueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Synthetic messages only. Never loads social accounts, posts or Meta credentials.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "socialhub.mock-queue.enabled", havingValue = "true")
public class MockQueueTestDriver {
    private static final Logger terminalLog = LoggerFactory.getLogger("socialhub.mock.terminal");
    private static final Logger backendLog = LoggerFactory.getLogger(MockQueueTestDriver.class);

    @Bean
    @ConditionalOnProperty(name = "socialhub.mock-queue.seed", havingValue = "true")
    ApplicationRunner seedSyntheticMessages(
            RabbitTemplate rabbit,
            @Value("${socialhub.mock-queue.accounts:100}") int accounts,
            @Value("${socialhub.mock-queue.jobs-per-account:10}") int jobsPerAccount,
            @Value("${socialhub.mock-queue.inject-duplicates:10}") int duplicates,
            @Value("${socialhub.mock-queue.run-confirm:}") String confirmation,
            @Value("${socialhub.mock-queue.terminal-enabled:false}") boolean terminalEnabled) {
        return args -> {
            if (!"MOCK_ONLY".equals(confirmation)) {
                throw new IllegalStateException("MOCK seed blocked: set SOCIALHUB_MOCK_RUN_CONFIRM=MOCK_ONLY");
            }
            if (accounts < 1 || accounts > 1000 || jobsPerAccount < 1 || jobsPerAccount > 1000
                    || (long) accounts * jobsPerAccount > 100_000
                    || duplicates < 0 || duplicates > accounts * jobsPerAccount) {
                throw new IllegalArgumentException("Unsafe synthetic load dimensions");
            }
            // Allow the separate PowerShell viewer to attach before dispatching any messages.
            // Only relevant to the interactive MOCK demo; the worker does not depend on the viewer.
            if (terminalEnabled) {
                try {
                    Thread.sleep(1800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("MOCK seed interrupted", e);
                }
            }
            final int total = accounts * jobsPerAccount;
            backendLog.info("[MOCK ENGINE] Seeding synthetic RabbitMQ messages | accounts={} jobs={}", accounts, total);
            terminalLog.info("[MOCK LOAD] SEED START | accounts={} jobsPerAccount={} jobs={} duplicateMessages={} queue={}",
                    accounts, jobsPerAccount, total, duplicates, MockQueueConfiguration.QUEUE);
            for (int a = 1; a <= accounts; a++) {
                for (int j = 1; j <= jobsPerAccount; j++) {
                    String jobId = "MOCK-A%04d-J%04d".formatted(a, j);
                    rabbit.convertAndSend(MockQueueConfiguration.QUEUE, jobId + "|SUCCESS");
                    int index = (a - 1) * jobsPerAccount + j;
                    if (index <= duplicates) {
                        rabbit.convertAndSend(MockQueueConfiguration.QUEUE, jobId + "|SUCCESS");
                    }
                }
            }
            terminalLog.info("[MOCK LOAD] SEED COMPLETE | expectedUniqueJobs={} publishedMessages={} | no Meta API calls",
                    total, total + duplicates);
            backendLog.info("[MOCK ENGINE] Enqueued messages={} (including {} duplicates)", total + duplicates, duplicates);
        };
    }
}
