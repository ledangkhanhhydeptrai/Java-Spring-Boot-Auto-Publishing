package com.socialhub.dryrun;

import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/dry-run")
@RequiredArgsConstructor
public class DryRunController {

    private final DryRunService dryRunService;
    private final AccessService accessService;

    @GetMapping("/preview")
    public List<DryRunService.DryRunResult> preview(
            @PathVariable UUID workspaceId
    ) {
        accessService.admin(workspaceId);
        return dryRunService.preview(workspaceId);
    }
}