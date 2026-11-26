package com.socialhub.googlesheets.controller;

import com.socialhub.googlesheets.service.GoogleSheetsWritebackService;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/google-sheets/connections/{connectionId}/writeback")
public class GoogleSheetsWritebackController {
    private final AccessService access;
    private final GoogleSheetsWritebackService service;

    @GetMapping("/preview")
    public GoogleSheetsWritebackService.Report preview(
            @PathVariable UUID workspaceId, @PathVariable UUID connectionId,
            @RequestParam UUID importId,
            @RequestParam(defaultValue = "2") int fromRow,
            @RequestParam(defaultValue = "10001") int toRow,
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(defaultValue = "FACEBOOK_PAGE") String platform) {
        access.admin(workspaceId);
        return service.preview(workspaceId, connectionId, importId, fromRow, toRow, limit, platform);
    }

    @GetMapping("/preview-all")
    public GoogleSheetsWritebackService.Report previewAll(
            @PathVariable UUID workspaceId, @PathVariable UUID connectionId,
            @RequestParam(defaultValue = "FACEBOOK_PAGE") String platform) {
        access.admin(workspaceId);
        return service.previewAll(workspaceId, connectionId, platform);
    }

    @PostMapping("/sync")
    public GoogleSheetsWritebackService.Report sync(
            @PathVariable UUID workspaceId, @PathVariable UUID connectionId,
            @RequestParam UUID importId,
            @RequestParam(defaultValue = "2") int fromRow,
            @RequestParam(defaultValue = "10001") int toRow,
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(defaultValue = "FACEBOOK_PAGE") String platform) {
        access.admin(workspaceId);
        return service.writeNow(workspaceId, connectionId, importId, fromRow, toRow, limit, platform);
    }

    @PatchMapping
    public GoogleSheetsWritebackService.SwitchStatus enabled(
            @PathVariable UUID workspaceId, @PathVariable UUID connectionId,
            @RequestParam boolean enabled) {
        access.admin(workspaceId);
        return service.setEnabled(workspaceId, connectionId, enabled);
    }
}
