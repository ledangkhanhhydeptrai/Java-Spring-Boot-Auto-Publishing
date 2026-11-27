package com.socialhub.grouppublishingtask.queue;

import com.socialhub.grouppublishingtask.automation.GroupTaskQueueService;
import com.socialhub.security.AccessService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/group-automation/queue")
public class GroupBackgroundDispatchController {
    private final AccessService access;
    private final GroupTaskQueueService tasks;
    private final GroupBackgroundDispatch background;

    public GroupBackgroundDispatchController(AccessService access, GroupTaskQueueService tasks,
                                             GroupBackgroundDispatch background) {
        this.access = access;
        this.tasks = tasks;
        this.background = background;
    }

    public record Report(long pendingDue, GroupBackgroundDispatch.Status diagnostic) {
    }

    @GetMapping("/background-status")
    public Report status(@PathVariable UUID workspaceId, @RequestParam UUID facebookGroupId) {
        access.workspace(workspaceId);
        long pendingDue = tasks.pendingDue(workspaceId, facebookGroupId);
        return new Report(pendingDue, background.status(workspaceId, facebookGroupId));
    }
}
