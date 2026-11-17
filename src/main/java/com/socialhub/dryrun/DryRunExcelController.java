package com.socialhub.dryrun;

import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/dry-run/excel")
@RequiredArgsConstructor
public class DryRunExcelController {
    private final DryRunExcelService service;
    private final AccessService access;

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DryRunExcelService.PreviewResult preview(
            @PathVariable UUID workspaceId,
            @RequestPart("file") MultipartFile file) {
        access.admin(workspaceId);
        return service.preview(file);
    }
}
