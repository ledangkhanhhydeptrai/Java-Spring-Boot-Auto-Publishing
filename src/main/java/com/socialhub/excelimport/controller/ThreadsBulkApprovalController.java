package com.socialhub.excelimport.controller;

import com.socialhub.excelimport.service.implement.ThreadsBulkApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/excel-imports/{importId}/threads-prepare")
public class ThreadsBulkApprovalController {
    private final ThreadsBulkApprovalService service;

    @PostMapping
    public ThreadsBulkApprovalService.Result prepare(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @RequestBody ThreadsBulkApprovalService.Request request) {
        return service.run(workspaceId, importId, request);
    }
}
