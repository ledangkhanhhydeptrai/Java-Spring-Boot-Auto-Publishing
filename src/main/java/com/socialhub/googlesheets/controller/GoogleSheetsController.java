package com.socialhub.googlesheets.controller;

import com.socialhub.googlesheets.dto.GoogleSheetsDto;
import com.socialhub.googlesheets.service.GoogleSheetsPreviewService;
import com.socialhub.security.AccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/google-sheets")
public class GoogleSheetsController {
    private final AccessService access;
    private final GoogleSheetsPreviewService previewService;

    // Read-only: no database writes, no publishing jobs, no Meta/Facebook calls.
    @PostMapping("/preview")
    public GoogleSheetsDto.Preview preview(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody GoogleSheetsDto.PreviewRequest request) {
        access.admin(workspaceId);
        return previewService.preview(request);
    }
}
