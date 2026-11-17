
package com.socialhub.publishingjob.service.implement;

import com.socialhub.publishingjob.engine.FanpageQueueProcessor;
import com.socialhub.threads.ThreadsPublishingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(prefix = "app.runtime", name = "role", havingValue = "api", matchIfMissing = true)
@RequiredArgsConstructor
public class PublisherScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(PublisherScheduler.class);

    private final FanpageQueueProcessor processor;
    private final PublishingJobRecoveryService recoveryService;
    private final ThreadsPublishingService threadsPublisher;

    @Value("${app.threads.publisher-enabled:false}")
    private boolean threadsEnabled;

    @Value("${app.facebook.publishing-mode:MOCK}")
    private String publishingMode;

    @Value("${app.publisher-enabled:false}")
    private boolean facebookEnabled;

    @Value("${app.facebook.bulk-enabled:false}")
    private boolean bulkEnabled;

    @Value("${app.automation.live.enabled:false}")
    private boolean automationLiveEnabled;

    @Scheduled(fixedDelayString = "${app.publisher-poll-ms:15000}")
    public void tick() {

        log.info(
                "[SCHEDULER] tick | facebookEnabled={} | threadsEnabled={} | mode={} | bulkEnabled={} | automationLiveEnabled={}",
                facebookEnabled,
                threadsEnabled,
                publishingMode,
                bulkEnabled,
                automationLiveEnabled
        );

        // Phase 2C LIVE uses its own queue worker.
        // Never run the legacy poller concurrently.
        if (automationLiveEnabled) {

            if (facebookEnabled || threadsEnabled) {
                log.error(
                        "[AUTOMATION] Legacy publisher flags conflict with Phase 2C LIVE; no legacy jobs processed"
                );
            } else {
                log.debug(
                        "[AUTOMATION] Phase 2C LIVE active; skipping legacy publisher"
                );
            }

            return;
        }

        try {
            processFacebook();
        } catch (Exception ex) {
            log.error(
                    "[FACEBOOK] Scheduled processing failed",
                    ex
            );
        }

        try {
            processThreads();
        } catch (Exception ex) {
            log.error(
                    "[THREADS] Scheduled processing failed",
                    ex
            );
        }
    }

    private void processFacebook() {

        if (!facebookEnabled) {
            log.info("[FACEBOOK] Publisher disabled");
            return;
        }

        boolean liveMode =
                "LIVE".equalsIgnoreCase(publishingMode);

        boolean bulkLiveMode =
                liveMode && bulkEnabled;

        if (!liveMode) {

            int recovered = recoveryService
                    .recoverExpiredJobsForPlatform("FACEBOOK_PAGE");

            if (recovered > 0) {
                log.warn(
                        "[FACEBOOK MOCK] Recovered {} expired job(s)",
                        recovered
                );
            }

        } else if (bulkLiveMode) {

            int recovered = recoveryService
                    .recoverExpiredFacebookBulkJobs();

            if (recovered > 0) {
                log.warn(
                        "[FACEBOOK LIVE BULK] {} job(s) require manual review",
                        recovered
                );
            }

        } else {

            log.debug(
                    "[FACEBOOK LIVE SINGLE] Recovery disabled"
            );
        }

        log.info(
                "[SCHEDULER] Calling FanpageQueueProcessor.processBatch()"
        );

        processor.processBatch();

        log.info(
                "[SCHEDULER] FanpageQueueProcessor.processBatch() finished"
        );
    }

    private void processThreads() {

        if (!threadsEnabled) {
            log.info("[THREADS] Publisher disabled");
            return;
        }

        log.info(
                "[SCHEDULER] Calling ThreadsPublishingService.processDueBatch()"
        );

        int processed = threadsPublisher.processDueBatch();

        log.info(
                "[SCHEDULER] Threads batch finished | processed={}",
                processed
        );
    }
}
