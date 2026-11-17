package com.socialhub.automation.health;

import java.time.OffsetDateTime;

/** Pure classification. This never decrypts, logs, or returns an OAuth token. */
public final class AccountHealthEvaluator {
    private AccountHealthEvaluator() { }

    public enum State { DISCONNECTED, TOKEN_MISSING, TOKEN_EXPIRED, EXPIRING_SOON, READY }

    public static State evaluate(String connectionStatus, boolean tokenPresent,
                                 OffsetDateTime expiresAt, OffsetDateTime now) {
        if (!"CONNECTED".equals(connectionStatus)) return State.DISCONNECTED;
        if (!tokenPresent) return State.TOKEN_MISSING;
        if (expiresAt != null && !expiresAt.isAfter(now)) return State.TOKEN_EXPIRED;
        if (expiresAt != null && !expiresAt.isAfter(now.plusHours(24))) return State.EXPIRING_SOON;
        return State.READY;
    }
}
