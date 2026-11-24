package com.socialhub.googlesheets.controller;

import com.socialhub.googlesheets.dto.Sh26BulkDto;
import com.socialhub.googlesheets.service.Sh26BulkDraftService;
import com.socialhub.security.AccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/google-sheets/sh26")
public class Sh26BulkController {
    private final AccessService access;
    private final Sh26BulkDraftService service;

    @GetMapping("/imports")
    public List<Sh26BulkDto.Imported> imports(@PathVariable UUID workspaceId) {
        access.admin(workspaceId);
        return service.existingImports(workspaceId);
    }

    @GetMapping("/existing-posts")
    public List<Sh26BulkDto.ExistingPost> existingPosts(@PathVariable UUID workspaceId) {
        access.admin(workspaceId);
        return service.existingPosts(workspaceId);
    }

    @PostMapping("/import-preview")
    public Sh26BulkDto.Preview preview(@PathVariable UUID workspaceId,
                                       @Valid @RequestBody Sh26BulkDto.Request request) {
        access.admin(workspaceId);
        return service.preview(workspaceId, request);
    }

    @PostMapping("/import-confirm")
    public Sh26BulkDto.Result confirm(@PathVariable UUID workspaceId,
                                      @Valid @RequestBody Sh26BulkDto.Confirm request) {
        access.admin(workspaceId);
        return service.confirm(workspaceId, request);
    }
}
