package com.socialhub.automation.live;

import com.socialhub.publishingjob.engine.FanpageQueueProcessor;
import com.socialhub.threads.ThreadsPublishingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Explicitly gated Phase 2C entry point. Does NOT subscribe to the existing
 * observation queue and cannot be triggered by RabbitMQ in this release.
 * Only a future, separately reviewed LIVE queue consumer may invoke it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationExactJobExecutor {
    private final JdbcTemplate db;
    private final FanpageQueueProcessor facebook;
    private final ThreadsPublishingService threads;

    @Value("${app.automation.live.enabled:false}")
    private boolean enabled;

    @Value("${app.publisher-enabled:false}")
    private boolean legacyFacebookEnabled;

    @Value("${app.threads.publisher-enabled:false}")
    private boolean legacyThreadsEnabled;

    /**
     * No HTTP mapping: only future internal worker integrations may call this.
     * Guards are intentionally duplicated in atomic platform-specific claims.
     */
    public void execute(UUID jobId) {
        if (!enabled) {
            throw new IllegalStateException("Phase 2C LIVE executor is disabled");
        }
        if (legacyFacebookEnabled || legacyThreadsEnabled) {
            throw new IllegalStateException("Disable both legacy publishers before Phase 2C LIVE");
        }
        if (jobId == null) {
            throw new IllegalArgumentException("jobId is required");
        }
        String platform = db.query("""
                SELECT a.platform
                FROM publishing_jobs j
                JOIN social_accounts a ON a.id=j.social_account_id
                WHERE j.id=?
                """, (rs, i) -> rs.getString(1), jobId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Publishing Job not found"));
        switch (platform) {
            case "FACEBOOK_PAGE" -> facebook.publishQueuedJob(jobId);
            case "THREADS" -> {
                var result = threads.publishQueuedJob(jobId);
                log.info("[AUTOMATION EXACT] Threads job={} finished status={}", jobId, result.status());
            }
            default -> throw new IllegalStateException("Unsupported platform: " + platform);
        }
    }
}
