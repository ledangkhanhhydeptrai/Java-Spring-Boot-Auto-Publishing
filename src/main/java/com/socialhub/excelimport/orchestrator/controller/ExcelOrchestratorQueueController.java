package com.socialhub.excelimport.orchestrator.controller;

import com.socialhub.excelimport.orchestrator.service.ExcelOrchestratorQueueService;
import com.socialhub.excelimport.orchestrator.service.ExcelPublishingOrchestratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/excel-imports/{importId}/orchestrator/queue")
public class ExcelOrchestratorQueueController {
    private final ExcelOrchestratorQueueService queue;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExcelOrchestratorQueueService.Accepted confirm(
            Authentication authentication,
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @RequestBody ExcelPublishingOrchestratorService.Request request) {
        return queue.enqueue(workspaceId,importId,UUID.fromString(authentication.getName()),request);
    }

    @GetMapping("/{queueId}")
    public ExcelOrchestratorQueueService.QueueStatus get(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @PathVariable UUID queueId) {
        return queue.get(workspaceId, importId,queueId);
    }
}
