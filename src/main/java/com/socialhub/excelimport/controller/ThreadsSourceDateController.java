package com.socialhub.excelimport.controller;

import com.socialhub.excelimport.service.implement.ThreadsSourceDateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/excel-imports/{importId}/threads-source-dates")
public class ThreadsSourceDateController {
    private final ThreadsSourceDateService service;

    @PostMapping
    public ThreadsSourceDateService.Result process(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @RequestBody ThreadsSourceDateService.Request request) {
        return service.run(workspaceId, importId, request);
    }
}
