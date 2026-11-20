package com.socialhub.googlesheets.controller;

import com.socialhub.googlesheets.dto.GoogleSheetsPublishingBridgeDto;
import com.socialhub.googlesheets.service.GoogleSheetsPublishingBridgeService;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/google-sheets/connections/{connectionId}/publishing-bridge")
public class GoogleSheetsPublishingBridgeController {
    private final AccessService access;
    private final GoogleSheetsPublishingBridgeService bridge;

    /** Compare staging vs a known Excel import: this endpoint never schedules or publishes. */
    @GetMapping("/preview")
    public GoogleSheetsPublishingBridgeDto.Preview preview(
            @PathVariable UUID workspaceId,
            @PathVariable UUID connectionId,
            @RequestParam UUID importId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        access.admin(workspaceId);
        return bridge.preview(workspaceId, connectionId, importId, limit, offset);
    }
}
