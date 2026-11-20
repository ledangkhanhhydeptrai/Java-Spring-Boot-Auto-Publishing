package com.socialhub.googlesheets.controller;

import com.socialhub.excelimport.orchestrator.service.ExcelOrchestratorQueueService;
import com.socialhub.excelimport.orchestrator.service.ExcelPublishingOrchestratorService;
import com.socialhub.googlesheets.service.GoogleSheetsQueueBridgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/google-sheets/connections/{connectionId}/publishing-bridge/queue")
public class GoogleSheetsQueueBridgeController {
    private final GoogleSheetsQueueBridgeService bridge;

    @PostMapping("/preview")
    public GoogleSheetsQueueBridgeService.Preview preview(
            Authentication auth,
            @PathVariable UUID workspaceId,
            @PathVariable UUID connectionId,
            @RequestParam UUID importId,
            @RequestBody ExcelPublishingOrchestratorService.Request request) {
        return bridge.preview(workspaceId, connectionId, importId, UUID.fromString(auth.getName()), request);
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExcelOrchestratorQueueService.Accepted confirm(
            Authentication auth,
            @PathVariable UUID workspaceId,
            @PathVariable UUID connectionId,
            @RequestBody GoogleSheetsQueueBridgeService.ConfirmRequest request) {
        return bridge.confirm(workspaceId, connectionId, UUID.fromString(auth.getName()), request);
    }
}
