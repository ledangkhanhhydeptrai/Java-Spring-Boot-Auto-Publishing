package com.socialhub.grouppublishingtask.automation;

import com.socialhub.grouppublishingtask.excel.GroupSheetQuickPlanController;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/group-automation")
public class GroupAutomationController {
    private final GroupAutomationService service;
    public GroupAutomationController(GroupAutomationService service) { this.service=service; }

    @PostMapping("/preview")
    public GroupSheetQuickPlanController.Result preview(@PathVariable UUID workspaceId,
            @RequestBody GroupSheetQuickPlanController.Request request) {
        return service.preview(workspaceId,request);
    }

    @PostMapping("/confirm")
    public GroupSheetQuickPlanController.Result confirm(@PathVariable UUID workspaceId,
            @RequestBody GroupSheetQuickPlanController.Request request) {
        return service.confirm(workspaceId,request);
    }

    @GetMapping("/status")
    public GroupAutomationService.Status status(@PathVariable UUID workspaceId,
            @RequestParam UUID facebookGroupId) {
        return service.status(workspaceId,facebookGroupId);
    }

    @GetMapping("/due")
    public List<GroupTaskQueueService.DueTask> due(@PathVariable UUID workspaceId,
            @RequestParam UUID facebookGroupId,@RequestParam(defaultValue="20") int limit) {
        return service.due(workspaceId,facebookGroupId,limit);
    }
}
