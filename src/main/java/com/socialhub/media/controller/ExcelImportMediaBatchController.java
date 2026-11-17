package com.socialhub.media.controller;

import com.socialhub.media.service.implement.ExcelImportMediaBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/excel-imports")
public class ExcelImportMediaBatchController {

    private final ExcelImportMediaBatchService batchService;

    @PostMapping("/{importId}/media/process")
    public ExcelImportMediaBatchService.BatchResult process(
            @PathVariable UUID workspaceId,
            @PathVariable UUID importId,
            @RequestParam(defaultValue = "5") int maxPosts,
            @RequestParam(required = false) Integer afterRowNumber
    ) {
        return batchService.process(workspaceId, importId,
                maxPosts, afterRowNumber);
    }
}
