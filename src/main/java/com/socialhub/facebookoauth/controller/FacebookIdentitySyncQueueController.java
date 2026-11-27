package com.socialhub.facebookoauth.controller;

import com.socialhub.facebookoauth.service.implement.FacebookIdentitySyncQueue;
import com.socialhub.security.AccessService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/facebook-identities/sync-runs")
public class FacebookIdentitySyncQueueController {
    private final AccessService access;
    private final FacebookIdentitySyncQueue queue;
    public FacebookIdentitySyncQueueController(AccessService access, FacebookIdentitySyncQueue queue) {
        this.access=access; this.queue=queue;
    }
    public record StartRequest(String facebookUserId) {}
    @PostMapping
    public FacebookIdentitySyncQueue.Run start(@PathVariable UUID workspaceId, @RequestBody StartRequest body) {
        access.admin(workspaceId);
        return queue.start(workspaceId,body.facebookUserId());
    }
    @GetMapping("/latest")
    public FacebookIdentitySyncQueue.Latest latest(@PathVariable UUID workspaceId) {
        access.admin(workspaceId);
        return queue.latest(workspaceId);
    }
}
