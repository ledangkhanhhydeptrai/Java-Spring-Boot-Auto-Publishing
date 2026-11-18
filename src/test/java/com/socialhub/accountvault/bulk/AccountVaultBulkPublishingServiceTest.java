package com.socialhub.accountvault.bulk;

import com.socialhub.common.ApiException;
import com.socialhub.facebookoauth.repository.FacebookPageConnectionRepository;
import com.socialhub.publishingjob.bulk.service.FacebookBulkRunService;
import com.socialhub.publishingjob.repository.PublishingJobRepository;
import com.socialhub.publishingjob.service.implement.PublishingJobServiceImpl;
import com.socialhub.security.AccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountVaultBulkPublishingServiceTest {
    private AccountVaultBulkPublishingService service;
    private JdbcTemplate jdbc;
    private UUID workspaceId;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        AccessService access = mock(AccessService.class);
        service = new AccountVaultBulkPublishingService(
                jdbc, access,
                mock(PublishingJobServiceImpl.class),
                mock(FacebookBulkRunService.class),
                mock(PublishingJobRepository.class),
                mock(FacebookPageConnectionRepository.class)
        );
        workspaceId = UUID.randomUUID();
    }

    private AccountVaultBulkPublishingService.BulkRequest request(
            String platform, List<UUID> posts, List<UUID> accounts) {
        return new AccountVaultBulkPublishingService.BulkRequest(
                platform, posts, accounts, OffsetDateTime.now().plusHours(1), false);
    }

    @Test
    void rejectsMoreThanOneHundredPairsBeforeDatabaseAccess() {
        List<UUID> posts = new ArrayList<>();
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 11; i++) posts.add(UUID.randomUUID());
        for (int i = 0; i < 10; i++) accounts.add(UUID.randomUUID());
        assertThrows(ApiException.class,
                () -> service.preview(workspaceId, request("FACEBOOK_PAGE", posts, accounts)));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsDuplicateAccountsBeforeDatabaseAccess() {
        UUID account = UUID.randomUUID();
        assertThrows(ApiException.class,
                () -> service.preview(workspaceId, request(
                        "THREADS", List.of(UUID.randomUUID()), List.of(account, account))));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsUnapprovedPlatformBeforeDatabaseAccess() {
        assertThrows(ApiException.class,
                () -> service.preview(workspaceId, request(
                        "PERSONAL_FACEBOOK", List.of(UUID.randomUUID()), List.of(UUID.randomUUID()))));
        verifyNoInteractions(jdbc);
    }

    @Test
    void schedulingIsDisabledByDefaultEvenWithConfirmPublish() {
        var request = new AccountVaultBulkPublishingService.BulkRequest(
                "FACEBOOK_PAGE", List.of(UUID.randomUUID()), List.of(UUID.randomUUID()),
                OffsetDateTime.now().plusHours(1), true);
        assertThrows(ApiException.class,
                () -> service.schedule(workspaceId, UUID.randomUUID(), request));
        verifyNoInteractions(jdbc);
    }
}
