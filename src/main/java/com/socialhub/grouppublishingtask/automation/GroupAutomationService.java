package com.socialhub.grouppublishingtask.automation;

import com.socialhub.common.ApiException;
import com.socialhub.grouppublishingtask.excel.GroupSheetQuickPlanController;
import com.socialhub.security.AccessService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Uses the existing multi-import planner, including its preview-token validation. */
@Service
public class GroupAutomationService {
    private final AccessService access;
    private final GroupSheetQuickPlanController planner;
    private final GroupTaskQueueService queue;

    public GroupAutomationService(AccessService access, GroupSheetQuickPlanController planner,
                                  GroupTaskQueueService queue) {
        this.access=access; this.planner=planner; this.queue=queue;
    }

    public record Status(UUID workspaceId, UUID facebookGroupId, String publishingState,
                         boolean automaticPublishing, GroupTaskQueueService.Counts counts) { }

    public GroupSheetQuickPlanController.Result preview(UUID workspaceId,
                GroupSheetQuickPlanController.Request request) {
        access.admin(workspaceId);
        requireExplicitTarget(request);
        return planner.preview(workspaceId,request);
    }

    public GroupSheetQuickPlanController.Result confirm(UUID workspaceId,
                GroupSheetQuickPlanController.Request request) {
        access.admin(workspaceId);
        requireExplicitTarget(request);
        return planner.confirm(workspaceId,request);
    }

    public Status status(UUID workspaceId, UUID groupId) {
        access.workspace(workspaceId);
        return new Status(workspaceId,groupId,"NO_AUTHORIZED_PUBLISHER_CONFIGURED",false,
                queue.counts(workspaceId,groupId));
    }

    public List<GroupTaskQueueService.DueTask> due(UUID workspaceId, UUID groupId,int limit) {
        access.workspace(workspaceId);
        return queue.due(workspaceId,groupId,limit);
    }

    private static void requireExplicitTarget(GroupSheetQuickPlanController.Request request) {
        if (request == null || request.connectionId() == null || request.facebookGroupId() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST,"Explicit connectionId and facebookGroupId required");
    }
}
