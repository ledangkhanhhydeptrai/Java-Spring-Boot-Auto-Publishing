package com.socialhub.excelimport.controller;

import com.socialhub.common.ApiException;
import com.socialhub.excelimport.dto.ExcelImportDto;
import com.socialhub.excelimport.service.implement.ExcelAutoPublishService;
import com.socialhub.excelimport.service.interfaces.ExcelImportService;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * One explicit bulk operation; one existing ExcelImport per authorized account.
 * No direct publishing and no Meta API calls are performed here.
 * Dispatcher/worker remain responsible for due scheduled jobs.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/excel-imports")
public class ExcelBulkImportController {
    private static final int MAX_ACCOUNTS = 100;
    private static final long MAX_JOBS_PER_REQUEST = 10_000;
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final AccessService access;
    private final ExcelImportService imports;
    private final ExcelAutoPublishService autoPublish;

    public record AccountResult(UUID accountId, UUID importId, String state,
                                int planned, int alreadyPlanned, int blocked, String message) {
    }

    public record BulkResult(int requestedAccounts, long maximumJobsFromFile,
                             int importedAccounts, int planned, int alreadyPlanned,
                             int blocked, List<AccountResult> results) {
    }

    @PostMapping(value = "/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkResult bulkImport(
            @PathVariable UUID workspaceId,
            @RequestPart("file") MultipartFile file,
            @RequestParam List<UUID> socialAccountIds,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime publishTime,
            @RequestParam(defaultValue = "false") boolean autoPlan,
            @RequestParam(defaultValue = "false") boolean confirmLive) {

        access.admin(workspaceId);
        if (socialAccountIds == null || socialAccountIds.isEmpty()
                || socialAccountIds.size() > MAX_ACCOUNTS
                || new LinkedHashSet<>(socialAccountIds).size() != socialAccountIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Supply 1-100 distinct socialAccountIds");
        }
        if (file == null || file.isEmpty() || file.getSize() > MAX_FILE_BYTES
                || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Provide a nonempty .xlsx file up to 10 MB");
        }
        if (publishTime == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "publishTime is required");
        }
        if (autoPlan && !confirmLive) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Explicit confirmLive=true required for autoPlan; scheduled jobs can publish LIVE when enabled");
        }
        if (autoPlan) autoPublish.validateFile(file);

        // A conservative upper bound: even blank rows can count as potential jobs.
        final long estimatedRows;
        try (var input = file.getInputStream(); var workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Workbook has no sheet");
            }
            estimatedRows = Math.max(0, workbook.getSheetAt(0).getLastRowNum());
        } catch (IOException | org.apache.poi.EncryptedDocumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot read .xlsx workbook");
        }
        if (estimatedRows == 0 || estimatedRows * socialAccountIds.size() > MAX_JOBS_PER_REQUEST) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Bulk request must include data and at most 10000 potential jobs");
        }

        int imported = 0, planned = 0, already = 0, blocked = 0;
        List<AccountResult> results = new ArrayList<>();
        for (UUID accountId : socialAccountIds) {
            try {
                // Existing service verifies CONNECTED status, workspace membership,
                // file hash uniqueness, row validity and persists posts per account.
                ExcelImportDto.View batch = imports.importExcel(workspaceId, file, accountId, publishTime);
                imported++;
                if (!autoPlan) {
                    results.add(new AccountResult(accountId, batch.id(), "IMPORTED", 0, 0, 0,
                            "Imported only; call auto-plan later"));
                    continue;
                }
                // Existing planner performs approval, scheduled Job creation and media checks.
                ExcelAutoPublishService.Result run = autoPublish.plan(workspaceId, batch.id());
                planned += run.planned();
                already += run.alreadyPlanned();
                blocked += run.blocked();
                results.add(new AccountResult(accountId, batch.id(), "PLANNED",
                        run.planned(), run.alreadyPlanned(), run.blocked(),
                        String.join("; ", run.errors())));
            } catch (RuntimeException ex) {
                log.warn("[EXCEL BULK] account={} failed: {}", accountId, ex.toString());
                results.add(new AccountResult(accountId, null, "ERROR", 0, 0, 0,
                        "Import/plan failed: " + ex.getClass().getSimpleName()));
            }
        }
        log.info("[EXCEL BULK] accounts={}/{} planned={} existing={} blocked={}",
                imported, socialAccountIds.size(), planned, already, blocked);
        return new BulkResult(socialAccountIds.size(), estimatedRows * socialAccountIds.size(),
                imported, planned, already, blocked, List.copyOf(results));
    }
}
