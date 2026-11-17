package com.socialhub.automation.health;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.socialhub.automation.health.AccountHealthEvaluator.State.*;

class AccountHealthEvaluatorTest {
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-14T16:00:00+07:00");

    @Test void disconnectedCannotBeReady() {
        assertEquals(DISCONNECTED, AccountHealthEvaluator.evaluate("DISCONNECTED", true, null, now));
    }
    @Test void connectedWithoutTokenIsMissing() {
        assertEquals(TOKEN_MISSING, AccountHealthEvaluator.evaluate("CONNECTED", false, null, now));
    }
    @Test void expiredTokenIsExpired() {
        assertEquals(TOKEN_EXPIRED, AccountHealthEvaluator.evaluate("CONNECTED", true, now, now));
    }
    @Test void expiringSoonIsReported() {
        assertEquals(EXPIRING_SOON, AccountHealthEvaluator.evaluate("CONNECTED", true, now.plusHours(8), now));
    }
    @Test void accountWithStoredTokenAndNoExpiryIsReadyAccordingToLocalEvidence() {
        assertEquals(READY, AccountHealthEvaluator.evaluate("CONNECTED", true, null, now));
    }
}
