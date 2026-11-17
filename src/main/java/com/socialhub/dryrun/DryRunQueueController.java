package com.socialhub.dryrun;

import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/dry-run")
@RequiredArgsConstructor
public class DryRunQueueController {
    private final AccessService access;
    private final DryRunQueueService queue;

    @PostMapping(value = "/excel/simulate-queue", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DryRunQueueService.QueueReport simulate(@PathVariable UUID workspaceId,
                                                   @RequestPart("file") MultipartFile file) {
        access.admin(workspaceId);
        return queue.simulate(file);
    }
}
