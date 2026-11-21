
package com.socialhub.excelimport.controller;

import com.socialhub.excelimport.dto.ExcelImportDto;
import com.socialhub.excelimport.service.interfaces.ExcelImportService;
import com.socialhub.excelimport.service.implement.ExcelPublishExportService;
import com.socialhub.excelimport.service.implement.ExcelAutoPublishService;
import com.socialhub.excelimport.service.implement.ExcelThreadsBridgeService;
import com.socialhub.mockqueue.ExcelMockRunService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/excel-imports")
public class ExcelImportController {

    private final ExcelImportService service;
    private final ExcelPublishExportService excelPublishExportService;
    private final ExcelAutoPublishService autoPublishService;
    private final ExcelThreadsBridgeService threadsBridgeService;
    private final ExcelMockRunService excelMockRunService;

    @Value("${socialhub.mock-excel.auto-start:false}")
    private boolean mockAutoStart;

    @Value("${socialhub.mock-queue.enabled:false}")
    private boolean mockQueueEnabled;

    @Value("${socialhub.mock-queue.run-confirm:}")
    private String mockRunConfirm;

    @Value("${app.automation.live.enabled:false}")
    private boolean liveEnabled;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExcelImportDto.View importExcel(
            @PathVariable UUID workspaceId,

            @RequestPart("file")
            @NotNull MultipartFile file,

            @RequestParam UUID socialAccountId,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
            LocalTime publishTime,

            @RequestParam(defaultValue = "false")
            boolean autoPublish
    ) {
        if (autoPublish) {
            autoPublishService.validateFile(file);
        }

        // Import Excel và lưu PostgreSQL.
        ExcelImportDto.View imported = service.importExcel(
                workspaceId,
                file,
                socialAccountId,
                publishTime
        );

        // Giữ nguyên luồng Auto Plan cũ.
        if (autoPublish) {
            autoPublishService.plan(workspaceId, imported.id());
        }

        // Tự động START MOCK sau khi Import thành công.
        if (mockAutoStart
                && mockQueueEnabled
                && !liveEnabled
                && "MOCK_ONLY".equals(mockRunConfirm)) {

            log.info(
                    "[MOCK AUTO] STARTING_AFTER_IMPORT | workspace={} import={}",
                    workspaceId,
                    imported.id()
            );

            try {
                excelMockRunService.start(workspaceId, imported.id());

                log.info(
                        "[MOCK AUTO] STARTED_AFTER_IMPORT | workspace={} import={}",
                        workspaceId,
                        imported.id()
                );
            } catch (Exception ex) {
                // Excel đã Import thành công.
                // Không xóa dữ liệu hoặc Import lại khi MOCK Start lỗi.
                log.error(
                        "[MOCK AUTO] START_FAILED | workspace={} import={}",
                        workspaceId,
                        imported.id(),
                        ex
                );
            }

        } else {
            log.info(
                    "[MOCK AUTO] SKIPPED | enabled={} queueEnabled={} liveEnabled={} confirmed={}",
                    mockAutoStart,
                    mockQueueEnabled,
                    liveEnabled,
                    "MOCK_ONLY".equals(mockRunConfirm)
            );
        }

        return imported;
    }

    @PostMapping("/{importId}/auto-plan")
    public ExcelAutoPublishService.Result autoPlan(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId
    ) {
        return autoPublishService.plan(workspaceId, importId);
    }

    /**
     * Explicit LIVE scheduling for one Excel source row.
     */
    @PostMapping("/{importId}/auto-plan/one")
    public ExcelAutoPublishService.Result autoPlanOne(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @RequestParam int sourceRowNumber,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            java.time.OffsetDateTime publishAt,
            @RequestParam(defaultValue = "false") boolean confirmLive
    ) {
        return autoPublishService.planOne(
                workspaceId, importId, sourceRowNumber, publishAt, confirmLive
        );
    }

    /**
     * Preview or confirm a batch of Excel dates. Preview never writes data.
     * Confirmation schedules jobs but does not activate a Facebook Bulk Run.
     */
    @PostMapping("/{importId}/auto-plan/batch")
    public ExcelAutoPublishService.BatchPlanResult autoPlanBatch(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @RequestBody ExcelAutoPublishService.BatchPlanRequest request
    ) {
        return autoPublishService.planBatch(workspaceId, importId, request);
    }

    /**
     * Reuse the original Excel/Google Sheets Post for a second publishing platform.
     * Read-only preview by default; explicit confirmation creates Threads Jobs.
     */
    @PostMapping("/{importId}/threads-plan")
    public ExcelThreadsBridgeService.Result threadsPlan(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @RequestBody ExcelThreadsBridgeService.Request request
    ) {
        return threadsBridgeService.plan(workspaceId, importId, request);
    }

    @GetMapping("/{importId}/publish-progress")
    public java.util.Map<String, Integer> publishProgress(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId
    ) {
        return excelPublishExportService.progress(workspaceId, importId);
    }

    @PostMapping(
            value = "/{importId}/export-published",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<byte[]> exportPublished(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @RequestPart("file") MultipartFile originalFile
    ) {
        byte[] result = excelPublishExportService.export(
                workspaceId,
                importId,
                originalFile
        );

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=excel-published.xlsx"
                )
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                ))
                .body(result);
    }

    @GetMapping
    public List<ExcelImportDto.View> list(
            @PathVariable UUID workspaceId
    ) {
        return service.list(workspaceId);
    }

    @GetMapping("/{importId}")
    public ExcelImportDto.View detail(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId
    ) {
        return service.detail(workspaceId, importId);
    }
}
