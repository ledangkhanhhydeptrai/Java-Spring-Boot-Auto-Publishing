package com.socialhub.excelimport;

import com.socialhub.excelimport.entity.ExcelImportRow;
import com.socialhub.excelimport.repository.ExcelImportRepository;
import com.socialhub.excelimport.repository.ExcelImportRowRepository;
import com.socialhub.excelimport.service.interfaces.ExcelImportService;
import com.socialhub.post.repository.PostRepository;
import com.socialhub.security.AccessService;
import com.socialhub.socialaccount.entity.SocialAccount;
import com.socialhub.socialaccount.repository.SocialAccountRepository;
import com.socialhub.workspace.entity.Workspace;
import com.socialhub.workspace.repository.WorkspaceRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayOutputStream;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.publisher-enabled=false",
        "app.facebook.publishing-mode=MOCK",
        "app.facebook.bulk-enabled=false",
        "app.facebook.multi-job-enabled=false",
        "app.threads.publisher-enabled=false",
        "app.meta.base-url=http://127.0.0.1:9090"
})
class ExcelImportRollbackTest {

    @Autowired
    private ExcelImportService excelImportService;

    @Autowired
    private ExcelImportRepository imports;

    @Autowired
    private PostRepository posts;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private SocialAccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AccessService access;

    // Current import flow creates DRAFT Post, not PublishingJob.
    // Force an error AFTER Post creation while saving its import-row history.
    @MockitoBean
    private ExcelImportRowRepository importRows;

    private UUID workspaceId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        String db = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        assertEquals("socialhub_test", db, "TEST BLOCKED: Must use socialhub_test database");

        Workspace workspace = new Workspace();
        workspace.setName("Excel Rollback Test " + UUID.randomUUID());
        workspace = workspaces.saveAndFlush(workspace);
        workspaceId = workspace.getId();

        SocialAccount account = new SocialAccount();
        account.setWorkspace(workspace);
        account.setPlatform("FACEBOOK_PAGE");
        account.setStatus("CONNECTED");
        account.setExternalAccountId("rollback-test-" + UUID.randomUUID());
        account.setDisplayName("Rollback Integration Test");
        account = accounts.saveAndFlush(account);
        accountId = account.getId();

        assertNotNull(accountId);
        when(access.workspace(workspaceId)).thenReturn(workspace);
    }

    @AfterEach
    void cleanUp() {
        if (accountId != null && accounts.existsById(accountId)) {
            accounts.deleteById(accountId);
            accounts.flush();
        }
        if (workspaceId != null && workspaces.existsById(workspaceId)) {
            workspaces.deleteById(workspaceId);
            workspaces.flush();
        }
    }

    @Test
    void shouldRollbackWhenImportRowHistoryCreationFails() throws Exception {
        doThrow(new IllegalStateException("SIMULATED_IMPORT_ROW_FAILURE"))
                .when(importRows).save(any(ExcelImportRow.class));

        MockMultipartFile file = new MockMultipartFile(
                "file", "rollback_test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                createExcel("ROLLBACK_TEST_" + UUID.randomUUID())
        );

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> excelImportService.importExcel(
                        workspaceId, file, accountId, LocalTime.of(9, 0))
        );
        assertEquals("SIMULATED_IMPORT_ROW_FAILURE", ex.getMessage());
        verify(importRows, times(1)).save(any(ExcelImportRow.class));

        assertTrue(imports.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).isEmpty(),
                "ExcelImport must be rolled back");
        assertTrue(posts.findByWorkspaceId(workspaceId).isEmpty(),
                "Post must be rolled back");

        Long jobCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM publishing_jobs j
                JOIN posts p ON p.id = j.post_id
                WHERE p.workspace_id = ?
                """, Long.class, workspaceId
        );
        assertEquals(0L, jobCount);
        assertTrue(workspaces.existsById(workspaceId));
        assertTrue(accounts.existsById(accountId));
    }

    private byte[] createExcel(String content) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Posts");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("nội dung");
            header.createCell(1).setCellValue("topic");
            header.createCell(2).setCellValue("ảnh 1");
            header.createCell(3).setCellValue("ảnh 2");
            header.createCell(4).setCellValue("ngày đăng");
            header.createCell(5).setCellValue("trạng thái");
            header.createCell(6).setCellValue("link bài đã đăng");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(content);
            row.createCell(1).setCellValue("Rollback Test");
            row.createCell(2).setCellValue("");
            row.createCell(3).setCellValue("");
            row.createCell(4).setCellValue("");
            row.createCell(5).setCellValue("Chưa đăng");
            row.createCell(6).setCellValue("");

            workbook.write(output);
            return output.toByteArray();
        }
    }
}
