
package com.socialhub.facebookoauth.controller;

import com.socialhub.facebookoauth.service.implement.FacebookLivePreflightService;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/facebook/preflight")
@RequiredArgsConstructor
public class FacebookLivePreflightController {

    private final FacebookLivePreflightService preflightService;
    private final AccessService accessService;
    private final JdbcTemplate db;

    /**
     * Read-only Facebook Page identity preflight.
     * <p>
     * - Requires Workspace Admin.
     * - Verifies Job belongs to Workspace.
     * - Never returns access tokens.
     * - Never publishes a Facebook post.
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<FacebookLivePreflightService.Result> checkJob(
            @PathVariable UUID workspaceId,
            @PathVariable UUID jobId
    ) {

        // 1. Authenticate and authorize Workspace Admin.
        accessService.admin(workspaceId);

        // 2. Ensure the Job belongs to this Workspace.
        Boolean belongsToWorkspace = db.queryForObject(
                """
                        SELECT EXISTS (
                            SELECT 1
                            FROM publishing_jobs j
                        
                            JOIN posts p
                                ON p.id = j.post_id
                        
                            JOIN social_accounts a
                                ON a.id = j.social_account_id
                        
                            WHERE j.id = ?
                              AND p.workspace_id = ?
                              AND a.workspace_id = ?
                              AND a.platform = 'FACEBOOK_PAGE'
                        )
                        """,
                Boolean.class,
                jobId,
                workspaceId,
                workspaceId
        );

        if (!Boolean.TRUE.equals(belongsToWorkspace)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Facebook Publishing Job not found in this Workspace"
            );
        }

        // 3. Read-only GET to Meta using the selected Page token.
        FacebookLivePreflightService.Result result =
                preflightService.checkJob(jobId);

        // 4. Return diagnostic result without exposing tokens.
        return ResponseEntity.ok(result);
    }
}
