package com.socialhub.mockqueue;

import com.socialhub.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "socialhub.mock-excel.enabled", havingValue = "true")
@RequestMapping("/api/workspaces/{workspaceId}/excel-imports/{importId}/mock-publishing")
public class ExcelMockRunController {
    private static final Logger log = LoggerFactory.getLogger(ExcelMockRunController.class);
    private final ExcelMockRunService service;

    public ExcelMockRunController(ExcelMockRunService service) {
        this.service = service;
    }

    // Simulation only: no Meta API, no real PublishingJob updates.
    @PostMapping("/start")
    public ExcelMockRunService.RunView start(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId) {
        log.info("[EXCEL MOCK] Start request | importId={}", importId);
        try {
            return service.start(workspaceId, importId);
        } catch (RuntimeException ex) {
            log.error("[EXCEL MOCK] Start failed", ex);
            throw ex;
        }
    }

    @GetMapping("/runs/{runId}")
    public ExcelMockRunService.RunView status(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @PathVariable UUID runId) {
        ExcelMockRunService.RunView result = service.status(workspaceId, runId);
        if (!result.importId().equals(importId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MOCK run not found for import");
        }
        return result;
    }

    @GetMapping("/latest")
    public ExcelMockRunService.RunView latest(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId) {
        return service.latestForImport(workspaceId, importId);
    }
}
