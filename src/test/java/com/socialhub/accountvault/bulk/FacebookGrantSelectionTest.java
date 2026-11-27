package com.socialhub.accountvault.bulk;

import com.socialhub.common.ApiException;
import com.socialhub.facebookoauth.entity.FacebookPageConnection;
import com.socialhub.facebookoauth.repository.FacebookPageConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No database or Meta calls: validates exact grant selection in the actual service. */
class FacebookGrantSelectionTest {
    private final UUID page = UUID.randomUUID();
    private final UUID post = UUID.randomUUID();
    private final OffsetDateTime due = OffsetDateTime.now().plusHours(1);
    private FacebookPageConnectionRepository connections;
    private AccountVaultBulkPublishingService service;

    @BeforeEach
    void setup() {
        connections = mock(FacebookPageConnectionRepository.class);
        service = new AccountVaultBulkPublishingService(null, null, null, null, null, connections);
    }

    private FacebookPageConnection grant(OffsetDateTime expires, String token) {
        var grant = new FacebookPageConnection();
        grant.setId(UUID.randomUUID());
        grant.setTokenExpiresAt(expires);
        grant.setAccessTokenEncrypted(token);
        return grant;
    }

    private FacebookPageConnection resolve(Map<UUID, UUID> bindings) {
        var request = new AccountVaultBulkPublishingService.BulkRequest(
                "FACEBOOK_PAGE", List.of(post), List.of(page), due, false, bindings);
        return ReflectionTestUtils.invokeMethod(service, "resolveConnection", request, page, due);
    }

    @Test
    void selectsExplicitGrantEvenWhenAnotherGrantIsFirst() {
        var first = grant(null, "test-only");
        var second = grant(due.plusHours(1), "test-only");
        when(connections.findEligible(page)).thenReturn(List.of(first, second));
        assertSame(second, resolve(Map.of(page, second.getId())));
        assertSame(first, resolve(Map.of(page, first.getId())));
    }

    @Test
    void rejectsWrongPageOrMissingGrantWithoutFallback() {
        var valid = grant(null, "test-only");
        when(connections.findEligible(page)).thenReturn(List.of(valid));
        assertThrows(ApiException.class, () -> resolve(Map.of(page, UUID.randomUUID())));
    }

    @Test
    void rejectsExpiredAndBlankTokenEvenWhenAnotherGrantIsValid() {
        var valid = grant(null, "test-only");
        var expired = grant(due.minusMinutes(1), "test-only");
        var blank = grant(null, " ");
        when(connections.findEligible(page)).thenReturn(List.of(valid, expired, blank));
        assertThrows(ApiException.class, () -> resolve(Map.of(page, expired.getId())));
        assertThrows(ApiException.class, () -> resolve(Map.of(page, blank.getId())));
    }

    @Test
    void legacyCallAcceptsOnlyUnambiguousConnection() {
        var first = grant(null, "test-only");
        var second = grant(null, "test-only");
        when(connections.findEligible(page)).thenReturn(List.of(first, second));
        assertThrows(ApiException.class, () -> resolve(null));
        when(connections.findEligible(page)).thenReturn(List.of(first));
        assertSame(first, resolve(null));
    }
}
