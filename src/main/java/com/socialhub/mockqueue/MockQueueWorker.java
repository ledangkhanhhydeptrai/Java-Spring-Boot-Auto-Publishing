package com.socialhub.mockqueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synthetic-only RabbitMQ consumer. This does not call Meta or any real publisher.
 * Logs each mock job so observers can watch real queue consumption in a terminal.
 */
@Component
@ConditionalOnProperty(name = "socialhub.mock-queue.enabled", havingValue = "true")
public class MockQueueWorker {
    private static final Logger terminalLog = LoggerFactory.getLogger("socialhub.mock.terminal");
    private static final Logger backendLog = LoggerFactory.getLogger(MockQueueWorker.class);
    private static final String MOCK_PAGE = "MOCK_PAGE_001";

    private final MockJobEngine engine;
    private final int expectedJobs;
    private final long demoDelayMs;
    private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicInteger completedUnique = new AtomicInteger();
    private final AtomicInteger duplicateMessages = new AtomicInteger();

    public MockQueueWorker(MockJobEngine engine,
                           @Value("${socialhub.mock-queue.accounts:100}") int accounts,
                           @Value("${socialhub.mock-queue.jobs-per-account:10}") int jobsPerAccount,
                           @Value("${socialhub.mock-queue.demo-delay-ms:${SOCIALHUB_MOCK_DEMO_DELAY_MS:0}}") long demoDelayMs) {
        if (demoDelayMs < 0 || demoDelayMs > 5000) {
            throw new IllegalArgumentException("SOCIALHUB_MOCK_DEMO_DELAY_MS must be between 0 and 5000");
        }
        this.engine = engine;
        this.expectedJobs = accounts * jobsPerAccount;
        this.demoDelayMs = demoDelayMs;
    }

    @RabbitListener(queues = MockQueueConfiguration.QUEUE, concurrency = "1-8")
    public void receive(String message) {
        if (message == null || !message.matches("MOCK-A[0-9]{4}-J[0-9]{4}\\|(SUCCESS|TRANSIENT_FAILURE|TIMEOUT_AFTER_ACCEPT)")) {
            throw new IllegalArgumentException("Invalid synthetic MOCK message format");
        }

        if (started.compareAndSet(false, true)) {
            backendLog.info("[MOCK ENGINE] RabbitMQ worker ACTIVE | target={} expectedJobs={}", MOCK_PAGE, expectedJobs);
        }
        String[] fields = message.split("\\|", 2);
        String jobId = fields[0];
        String account = jobId.substring(5, 10); // A0001 ... A0100

        terminalLog.info("[MOCK WORKER] START | account={} job={} page={} mode=SIMULATED", account, jobId, MOCK_PAGE);
        terminalLog.info("[MOCK WORKER] PUBLISHING | account={} job={} page={} mode=SIMULATED", account, jobId, MOCK_PAGE);
        if (demoDelayMs > 0) {
            try {
                Thread.sleep(demoDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while demonstrating mock job", e);
            }
        }

        MockJobEngine.Handled handled = engine.handleTracked(jobId, MockJobEngine.Outcome.valueOf(fields[1]));
        MockJobEngine.Result result = handled.result();
        int count = received.incrementAndGet();

        if (handled.duplicate()) {
            int duplicates = duplicateMessages.incrementAndGet();
            terminalLog.info("[MOCK WORKER] DUPLICATE_SKIPPED | account={} job={} page={} duplicatesSeen={}",
                    account, jobId, MOCK_PAGE, duplicates);
        } else if (result.status() == MockJobEngine.Status.COMPLETED) {
            terminalLog.info("[MOCK WORKER] PUBLISHED_SIMULATED | account={} job={} page={} attempt={}",
                    account, jobId, MOCK_PAGE, result.attempts());
        } else {
            terminalLog.info("[MOCK WORKER] {} | account={} job={} page={} attempt={} detail={}",
                    result.status(), account, jobId, MOCK_PAGE, result.attempts(), result.detail());
        }

        int unique = engine.acceptedCount();
        int prior = completedUnique.getAndAccumulate(unique, Math::max);
        if (unique > prior && (unique % 100 == 0 || unique == expectedJobs)) {
            terminalLog.info("[MOCK LOAD] progress | received={} completedUnique={}/{} duplicatesSeen={} terminalUnique={}",
                    count, unique, expectedJobs, duplicateMessages.get(), engine.terminalCount());
            backendLog.info("[MOCK ENGINE] Worker running | completedUnique={}/{} duplicateMessages={}",
                    unique, expectedJobs, duplicateMessages.get());
        }
        if (unique == expectedJobs && unique > prior) {
            terminalLog.info("[MOCK LOAD] COMPLETE | accountsSynthetic | uniqueJobs={} accepted={} duplicatesSeen={} noMetaCalls=true",
                    unique, engine.acceptedCount(), duplicateMessages.get());
            backendLog.info("[MOCK ENGINE] Worker COMPLETE | publishedSimulated={} expected={}",
                    unique, expectedJobs);
        }
    }
}
