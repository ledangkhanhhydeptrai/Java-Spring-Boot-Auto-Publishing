package com.socialhub.facebookoauth.controller;

import com.socialhub.security.AccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Read-only current alerts; only workspaces where the authenticated user is OWNER/ADMIN. */
@RestController
@RequestMapping("/api/facebook/identity-health")
public class FacebookIdentityHealthAlertController {
    private final JdbcTemplate jdbc;
    private final AccessService access;

    public FacebookIdentityHealthAlertController(JdbcTemplate jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public record WorkspaceAlert(UUID workspaceId, String workspaceName, long needsReauth, long checkErrors) {}

    @GetMapping("/alerts")
    public List<WorkspaceAlert> alerts() {
        return jdbc.query("""
                WITH scoped AS (
                    SELECT w.id AS workspace_id, w.name AS workspace_name, %s AS status
                    FROM workspace_members m
                    JOIN workspaces w ON w.id = m.workspace_id
                    JOIN facebook_authorized_identities i ON i.workspace_id = w.id
                    WHERE m.user_id = ? AND m.role IN ('OWNER','ADMIN')
                )
                SELECT workspace_id, workspace_name,
                    COUNT(*) FILTER (WHERE status IN ('REAUTH_REQUIRED','EXPIRED')) AS needs_reauth,
                    COUNT(*) FILTER (WHERE status = 'CHECK_ERROR') AS check_errors
                FROM scoped
                WHERE status IN ('REAUTH_REQUIRED','EXPIRED','CHECK_ERROR')
                GROUP BY workspace_id, workspace_name
                ORDER BY needs_reauth DESC, check_errors DESC, workspace_name, workspace_id
                """.formatted(FacebookIdentityController.EFFECTIVE_STATUS),
                (rs, n) -> new WorkspaceAlert(rs.getObject("workspace_id", UUID.class),
                    rs.getString("workspace_name"), rs.getLong("needs_reauth"), rs.getLong("check_errors")),
                access.userId());
    }
}
