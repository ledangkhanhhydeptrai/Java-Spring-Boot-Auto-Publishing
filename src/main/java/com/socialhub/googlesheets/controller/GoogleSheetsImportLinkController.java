package com.socialhub.googlesheets.controller;

import com.socialhub.common.ApiException;
import com.socialhub.googlesheets.service.GoogleSheetsImportLinkService;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/google-sheets/connections/{connectionId}/import-links")
public class GoogleSheetsImportLinkController {
    private final AccessService access;
    private final GoogleSheetsImportLinkService service;

    public record LinkRequest(List<UUID> importIds, boolean confirmWriteback) {}

    @PostMapping
    public GoogleSheetsImportLinkService.Report link(@PathVariable UUID workspaceId,
            @PathVariable UUID connectionId, @RequestBody LinkRequest request) {
        access.admin(workspaceId);
        if (request == null || !request.confirmWriteback()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Explicit writeback confirmation is required");
        }
        return service.linkImported(workspaceId, connectionId, request.importIds());
    }
}
