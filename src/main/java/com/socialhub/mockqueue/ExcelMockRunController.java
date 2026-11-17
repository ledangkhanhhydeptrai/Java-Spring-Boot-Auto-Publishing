package com.socialhub.mockqueue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "socialhub.mock-queue.enabled", havingValue = "true")
@RequestMapping("/api/workspaces/{workspaceId}/excel-imports/{importId}/mock-publishing")
public class ExcelMockRunController {
    private final ExcelMockRunService service;

    public ExcelMockRunController(ExcelMockRunService service) {
        this.service = service;
    }

    // NEVER calls Meta or alters live PublishingJob rows.
    @PostMapping("/start")
    public ExcelMockRunService.RunView start(@PathVariable UUID workspaceId, @PathVariable UUID importId) {
        return service.start(workspaceId, importId);
    }

    @GetMapping("/runs/{runId}")
    public ExcelMockRunService.RunView status(@PathVariable UUID workspaceId, @PathVariable UUID importId,
                                              @PathVariable UUID runId) {
        ExcelMockRunService.RunView result = service.status(workspaceId, runId);
        if (!result.importId().equals(importId))
            throw new com.socialhub.common.ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "MOCK run not found for import");
        return result;
    }

    @GetMapping("/latest")
    public ExcelMockRunService.RunView latest(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId
    ) {
        return service.latestForImport(workspaceId, importId);
    }
}
