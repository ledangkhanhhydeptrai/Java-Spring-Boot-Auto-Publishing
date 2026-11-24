package com.socialhub.excelimport;

import com.socialhub.common.ApiException;
import com.socialhub.excelimport.service.implement.ThreadsBulkApprovalService;
import com.socialhub.postapproval.service.interfaces.PostApprovalService;
import com.socialhub.security.AccessService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ThreadsBulkApprovalServiceTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final AccessService access = mock(AccessService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final PostApprovalService approvals = mock(PostApprovalService.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final ThreadsBulkApprovalService service = new ThreadsBulkApprovalService(
            db, access, entityManager, approvals, transactions);

    @Test
    void refusesFacebookImportEvenWhenThreadsAccountIsConnected() {
        UUID workspace = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        UUID threadsAccount = UUID.randomUUID();
        when(db.queryForObject(anyString(), eq(Boolean.class), any(), any(), any()))
                .thenReturn(false);
        var req = new ThreadsBulkApprovalService.Request(threadsAccount,
                50, null, null, true, false, null);

        ApiException thrown = assertThrows(ApiException.class,
                () -> service.run(workspace, importId, req));
        assertEquals("Import must belong to the selected CONNECTED Threads account; Facebook imports are not eligible",
                thrown.getMessage());
        verifyNoInteractions(approvals, transactions);
    }

    @Test
    void cannotCommitWithoutExplicitApprovalConfirmation() {
        var req = new ThreadsBulkApprovalService.Request(UUID.randomUUID(),
                50, null, null, false, false, null);
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.run(UUID.randomUUID(), UUID.randomUUID(), req));
        assertEquals("confirmApproval=true required for bulk approval", thrown.getMessage());
        verifyNoInteractions(db, approvals, transactions);
    }
}
