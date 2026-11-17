package com.socialhub.automation.health;

import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Read-only visibility, not an active token/permission probe against Meta. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/automation")
@RequiredArgsConstructor
public class AccountHealthController {
    private final AccessService access;
    private final JdbcTemplate db;

    public record AccountHealth(UUID accountId, String platform, String displayName,
                                String connectionStatus, String health,
                                OffsetDateTime tokenExpiresAt, boolean tokenStored) { }

    @GetMapping("/accounts/health")
    public List<AccountHealth> accounts(@PathVariable UUID workspaceId) {
        access.admin(workspaceId);
        OffsetDateTime now = OffsetDateTime.now();
        return db.query("""
                SELECT id, platform, display_name, status, token_expires_at,
                       (access_token_encrypted IS NOT NULL
                        AND BTRIM(access_token_encrypted) <> '') AS token_stored
                FROM social_accounts
                WHERE workspace_id = ?
                ORDER BY platform, display_name, id
                """, (rs, i) -> {
            // Access token value is deliberately never selected.
            OffsetDateTime expiry = rs.getObject("token_expires_at", OffsetDateTime.class);
            boolean stored = rs.getBoolean("token_stored");
            String status = rs.getString("status");
            String health = AccountHealthEvaluator.evaluate(status, stored, expiry, now).name();
            return new AccountHealth(rs.getObject("id", UUID.class), rs.getString("platform"),
                    rs.getString("display_name"), status, health, expiry, stored);
        }, workspaceId);
    }
}
