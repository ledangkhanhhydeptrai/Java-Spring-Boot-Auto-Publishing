package com.socialhub.grouppublishingtask.dispatch;


import com.socialhub.grouppublishingtask.automation.dispatch.GroupDispatchReadinessService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/group-automation/dispatch")
public class GroupDispatchReadinessController {
    private final GroupDispatchReadinessService service;

    public GroupDispatchReadinessController(GroupDispatchReadinessService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public GroupDispatchReadinessService.Status status(@PathVariable UUID workspaceId,
                                                       @RequestParam UUID facebookGroupId) {
        return service.status(workspaceId, facebookGroupId);
    }
}
