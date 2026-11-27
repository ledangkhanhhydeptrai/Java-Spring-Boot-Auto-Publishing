package com.socialhub.grouppublishingtask.queue;

import com.socialhub.grouppublishingtask.automation.GroupTaskQueueService;
import com.socialhub.security.AccessService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/group-automation/queue")
public class GroupQueueTransportController {
    private final AccessService access;
    private final GroupTaskQueueService tasks;
    private final GroupQueueTransportProbe probe;

    public GroupQueueTransportController(AccessService access, GroupTaskQueueService tasks,
                                         GroupQueueTransportProbe probe) {
        this.access = access;
        this.tasks = tasks;
        this.probe = probe;
    }

    @PostMapping("/probe")
    public GroupQueueTransportProbe.Report probe(@PathVariable UUID workspaceId,
                                                 @RequestParam UUID facebookGroupId) {
        access.admin(workspaceId);
        tasks.counts(workspaceId, facebookGroupId); // Validate Group ownership. Read-only.
        return probe.check();
    }
}
