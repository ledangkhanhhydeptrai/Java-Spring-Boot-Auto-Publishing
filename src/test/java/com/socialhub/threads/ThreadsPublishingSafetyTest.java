
package com.socialhub.threads;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import com.socialhub.socialaccount.repository.SocialAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ThreadsPublishingSafetyTest {

    private JdbcTemplate db;
    private TransactionTemplate tx;
    private AccessService access;
    private SocialAccountRepository accounts;
    private ThreadsPublishingService service;

    private UUID jobId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        tx = mock(TransactionTemplate.class);
        access = mock(AccessService.class);
        accounts = mock(SocialAccountRepository.class);

        jobId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();

        service = new ThreadsPublishingService(
                db,
                tx,
                access,
                accounts,
                new ObjectMapper()
        );

        // Chỉ bật manual publishing trong Unit Test.
        // Không ảnh hưởng application.yml hoặc ENV thực tế.
        ReflectionTestUtils.setField(
                service,
                "manualPublishingEnabled",
                true
        );
    }

    @Test
    void publishNow_manualDisabled_doesNotAccessDatabaseOrWorker() {

        ReflectionTestUtils.setField(
                service,
                "manualPublishingEnabled",
                false
        );

        assertThrows(
                ApiException.class,
                () -> service.publishNow(jobId)
        );

        verifyNoInteractions(
                db,
                tx,
                access,
                accounts
        );
    }

    @Test
    void publishNow_unknownJob_doesNotClaimOrContactAccount() {

        // JdbcTemplate mock trả danh sách rỗng.
        assertThrows(
                ApiException.class,
                () -> service.publishNow(jobId)
        );

        verifyNoInteractions(
                access,
                tx,
                accounts
        );
    }

    @Test
    void publishNow_wrongPlatform_doesNotClaimOrContactAccount() {

        mockJobInfo(
                "FACEBOOK_PAGE",
                "CONNECTED"
        );

        assertThrows(
                ApiException.class,
                () -> service.publishNow(jobId)
        );

        verify(access, times(1)).admin(workspaceId);

        verifyNoInteractions(
                tx,
                accounts
        );
    }

    @Test
    void publishNow_disconnectedAccount_doesNotClaimOrContactAccount() {

        mockJobInfo(
                "THREADS",
                "DISCONNECTED"
        );

        assertThrows(
                ApiException.class,
                () -> service.publishNow(jobId)
        );

        verify(access, times(1)).admin(workspaceId);

        verifyNoInteractions(
                tx,
                accounts
        );
    }

    @Test
    void publishNow_claimReturnsNoRows_doesNotContactAccount() {

        mockJobInfo(
                "THREADS",
                "CONNECTED"
        );

        // Cho TransactionTemplate thực thi callback.
        // JdbcTemplate vẫn là mock, không kết nối DB.
        doAnswer(invocation -> {

            TransactionCallback<?> callback =
                    invocation.getArgument(0);

            return callback.doInTransaction(null);

        }).when(tx).execute(
                any(TransactionCallback.class)
        );

        // Không stub kết quả UPDATE ... RETURNING.
        // db.query(...) trả danh sách rỗng.
        assertThrows(
                ApiException.class,
                () -> service.publishNow(jobId)
        );

        verify(access, times(1)).admin(workspaceId);

        verify(tx, times(1)).execute(
                any(TransactionCallback.class)
        );

        verifyNoInteractions(accounts);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockJobInfo(
            String platform,
            String accountStatus
    ) {

        Map<String, Object> jobInfo = Map.of(
                "workspaceId", workspaceId,
                "platform", platform,
                "accountStatus", accountStatus
        );

        doReturn(List.of(jobInfo))
                .when(db)
                .query(
                        contains("SELECT p.workspace_id"),
                        any(RowMapper.class),
                        eq(jobId)
                );
    }
}
