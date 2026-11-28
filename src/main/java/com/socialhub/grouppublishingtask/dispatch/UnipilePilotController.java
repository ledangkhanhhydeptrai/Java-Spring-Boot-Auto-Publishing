package com.socialhub.grouppublishingtask.dispatch;

import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Admin-only preview/status. No publishing or task mutations on GET. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/group-automation/unipile-pilot")
public class UnipilePilotController {
    private final AccessService access;
    private final JdbcTemplate db;
    private final boolean enabled;
    public UnipilePilotController(AccessService access,JdbcTemplate db,
                                  @Value("${app.group.unipile-pilot.enabled:false}") boolean enabled) {
        this.access=access; this.db=db; this.enabled=enabled;
    }
    public record Preview(UUID taskId,UUID postId,String externalGroupId,String text,String sha256,
                          String taskStatus,boolean due,boolean approved,boolean hasMedia,boolean eligibleText,
                          boolean eligibleMedia,int mediaCount,String mediaType,String mediaUrl,boolean pilotEnabled,String outboxState,String inboxState,String resultCode,String remotePostId,String permalink) {}
    @GetMapping("/preview")
    public Preview preview(@PathVariable UUID workspaceId,@RequestParam UUID facebookGroupId,@RequestParam UUID taskId) {
        access.admin(workspaceId);
        var rows=UnipilePilotPayload.load(db,workspaceId,facebookGroupId,taskId);
        if(rows.size()!=1) throw new ApiException(HttpStatus.NOT_FOUND,"Group task not found in workspace/group");
        var c=rows.getFirst();
        return db.queryForObject("""
                SELECT o.state,i.state,i.result_code,i.remote_post_id,t.published_url
                FROM group_publishing_tasks t LEFT JOIN group_dispatch_outbox o ON o.task_id=t.id
                LEFT JOIN group_dispatch_inbox i ON i.event_id=o.event_id WHERE t.id=?
                """,(rs,n)->new Preview(taskId,c.postId(),c.externalGroupId(),c.text(),c.sha256(),c.status(),c.due(),c.approved(),c.hasMedia(),c.eligibleText(),c.eligibleMedia(),c.mediaCount(),c.mediaType(),c.mediaUrl(),enabled,
                rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)),taskId);
    }
}
