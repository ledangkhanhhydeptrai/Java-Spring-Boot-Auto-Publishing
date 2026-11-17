package com.socialhub.automation.controller.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * PostgreSQL atomic admission counter for future queue workers.
 * NOT wired into the existing LIVE publishers: integration requires tests first.
 * The configured cap is a local safety ceiling, NOT a Meta API quota.
 */
@Service
@RequiredArgsConstructor
public class PublishingRateLimitService {
    private final JdbcTemplate db;

    @Value("${app.automation.rate-limit.max-publishes-per-account-per-minute:1}")
    private int maxPerMinute;

    @Transactional
    public boolean tryAcquire(UUID accountId) {
        if (accountId == null) throw new IllegalArgumentException("accountId required");
        if (maxPerMinute < 1 || maxPerMinute > 100) {
            throw new IllegalStateException("Invalid publishing rate-limit configuration");
        }

        // No slot allocated for disconnected or missing/expired token accounts.
        // This is a database preflight; API permissions must also be verified separately.
        Boolean eligible = db.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM social_accounts
                    WHERE id = ? AND platform IN ('FACEBOOK_PAGE', 'THREADS')
                      AND status = 'CONNECTED'
                      AND access_token_encrypted IS NOT NULL
                      AND BTRIM(access_token_encrypted) <> ''
                      AND (token_expires_at IS NULL OR token_expires_at > NOW())
                )
                """, Boolean.class, accountId);
        if (!Boolean.TRUE.equals(eligible)) return false;

        // One atomic SQL statement: concurrent worker processes cannot oversubscribe
        // the same account/window. A failed acquisition leaves the counter unchanged.
        Integer used = db.query("""
                INSERT INTO automation_publish_budgets
                    (social_account_id, window_start, used)
                VALUES (?, date_trunc('minute', NOW()), 1)
                ON CONFLICT (social_account_id, window_start)
                DO UPDATE SET used = automation_publish_budgets.used + 1
                WHERE automation_publish_budgets.used < ?
                RETURNING used
                """, rs -> rs.next() ? rs.getInt(1) : null, accountId, maxPerMinute);
        return used != null;
    }
}
