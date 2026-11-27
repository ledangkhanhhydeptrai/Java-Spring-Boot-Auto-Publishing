package com.socialhub.grouppublishingtask.excel;

import com.socialhub.security.AccessService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/group-writeback-target")
public class GroupWritebackTargetController {
    private final AccessService access;
    private final GroupWritebackTargetService targets;
    public GroupWritebackTargetController(AccessService access, GroupWritebackTargetService targets) {
        this.access=access; this.targets=targets;
    }
    @GetMapping
    public GroupWritebackTargetService.Target get(@PathVariable UUID workspaceId,@RequestParam UUID connectionId) {
        access.admin(workspaceId);
        return targets.get(workspaceId,connectionId);
    }
    @PostMapping
    public GroupWritebackTargetService.Target save(@PathVariable UUID workspaceId,@RequestBody GroupWritebackTargetService.Selection request) {
        access.admin(workspaceId);
        return targets.save(workspaceId,request);
    }
}
