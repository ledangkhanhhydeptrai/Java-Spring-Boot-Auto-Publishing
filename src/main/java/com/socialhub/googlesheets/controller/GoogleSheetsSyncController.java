package com.socialhub.googlesheets.controller;

import com.socialhub.googlesheets.dto.GoogleSheetsSyncDto;
import com.socialhub.googlesheets.service.GoogleSheetsSyncService;
import com.socialhub.security.AccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/google-sheets/connections")
public class GoogleSheetsSyncController {
    private final AccessService access;
    private final GoogleSheetsSyncService sync;

    @PostMapping
    public GoogleSheetsSyncDto.Connection connect(@PathVariable UUID workspaceId,
            @Valid @RequestBody GoogleSheetsSyncDto.ConnectRequest request) {
        access.admin(workspaceId);
        return sync.connect(workspaceId, request);
    }
    @GetMapping
    public List<GoogleSheetsSyncDto.Connection> list(@PathVariable UUID workspaceId) {
        access.admin(workspaceId);
        return sync.list(workspaceId);
    }
    @PostMapping("/{connectionId}/sync")
    public GoogleSheetsSyncDto.SyncResult syncNow(@PathVariable UUID workspaceId,
            @PathVariable UUID connectionId) {
        access.admin(workspaceId);
        return sync.syncNow(workspaceId,connectionId);
    }
    @GetMapping("/{connectionId}/rows")
    public List<GoogleSheetsSyncDto.StagedRow> rows(@PathVariable UUID workspaceId,
            @PathVariable UUID connectionId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        access.admin(workspaceId);
        return sync.rows(workspaceId,connectionId,limit,offset);
    }
    @PatchMapping("/{connectionId}/auto-sync")
    public GoogleSheetsSyncDto.Connection autoSync(@PathVariable UUID workspaceId,
            @PathVariable UUID connectionId, @RequestParam boolean enabled) {
        access.admin(workspaceId);
        return sync.setAutoSync(workspaceId,connectionId,enabled);
    }
}
