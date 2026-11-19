package com.socialhub.excelimport.controller;


import com.socialhub.excelimport.service.implement.ExcelBulkRunBridgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Excel -> existing Facebook BulkRun preparation, isolated from the current
 * ExcelImportController and FacebookBulkRunController to avoid breaking them.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/excel-imports/{importId}/bulk-runs")
public class ExcelBulkRunBridgeController {

    private final ExcelBulkRunBridgeService service;

    @GetMapping("/preview")
    public ExcelBulkRunBridgeService.Preview preview(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @RequestParam(defaultValue = "5") int maxJobs) {
        return service.preview(workspaceId, importId, maxJobs);
    }

    @PostMapping("/prepare")
    public ExcelBulkRunBridgeService.Preview prepare(
            Authentication authentication,
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @RequestBody ExcelBulkRunBridgeService.PrepareRequest request) {
        return service.prepare(workspaceId, importId,
                UUID.fromString(authentication.getName()), request);
    }
}
