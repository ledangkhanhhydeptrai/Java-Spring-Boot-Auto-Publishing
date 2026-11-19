package com.socialhub.excelimport.orchestrator.controller;

import com.socialhub.excelimport.orchestrator.service.ExcelPublishingOrchestratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/excel-imports/{importId}/orchestrator")
public class ExcelPublishingOrchestratorController {

    private final ExcelPublishingOrchestratorService service;

    @PostMapping("/run")
    public ExcelPublishingOrchestratorService.Result run(
            Authentication authentication,
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @RequestBody ExcelPublishingOrchestratorService.Request request) {
        return service.execute(workspaceId, importId,
                UUID.fromString(authentication.getName()), request);
    }
}
