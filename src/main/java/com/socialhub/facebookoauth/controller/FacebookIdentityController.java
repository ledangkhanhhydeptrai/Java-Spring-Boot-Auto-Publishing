package com.socialhub.facebookoauth.controller;

import com.socialhub.common.ApiException;
import com.socialhub.facebookoauth.service.implement.FacebookIdentityService;
import com.socialhub.security.AccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/facebook-identities")
public class FacebookIdentityController {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final FacebookIdentityService service;
    public FacebookIdentityController(JdbcTemplate jdbc, AccessService access, FacebookIdentityService service) {
        this.jdbc = jdbc; this.access = access; this.service = service;
    }
    public record Identity(String facebookUserId, String displayName, String examplePageName,
                           String status, long pageCount, OffsetDateTime lastSyncedAt) {}
    public record Directory(long totalElements, long totalPages, List<Identity> items) {}

    @GetMapping
    public Directory list(@PathVariable UUID workspaceId, @RequestParam(defaultValue="0") int page,
                          @RequestParam(defaultValue="20") int size) {
        access.admin(workspaceId);
        if (page < 0 || size < 1 || size > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "Thông tin phân trang không hợp lệ.");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM facebook_authorized_identities WHERE workspace_id = ?", Long.class, workspaceId);
        long count = total == null ? 0 : total;
        List<Identity> items = jdbc.query("""
            SELECT i.facebook_user_id, i.display_name, p.example_page_name, COALESCE(p.page_count, 0) AS page_count,
                i.last_synced_at,
                CASE WHEN i.user_token_encrypted IS NULL THEN 'REAUTH_REQUIRED'
                     WHEN i.token_expires_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                     ELSE 'STORED' END AS status
            FROM facebook_authorized_identities i
            LEFT JOIN LATERAL (
                SELECT MIN(a.display_name) AS example_page_name, COUNT(*) AS page_count
                FROM facebook_page_connections c JOIN social_accounts a ON a.id = c.social_account_id
                WHERE a.workspace_id = i.workspace_id AND a.platform = 'FACEBOOK_PAGE'
                  AND c.facebook_user_id = i.facebook_user_id
            ) p ON TRUE
            WHERE i.workspace_id = ? ORDER BY i.facebook_user_id LIMIT ? OFFSET ?
            """, (rs, n) -> new Identity(rs.getString("facebook_user_id"), rs.getString("display_name"),
                rs.getString("example_page_name"), rs.getString("status"), rs.getLong("page_count"),
                rs.getObject("last_synced_at", OffsetDateTime.class)), workspaceId, size, (long) page * size);
        return new Directory(count, (count + size - 1) / size, items);
    }

    @PostMapping("/{facebookUserId}/sync")
    public FacebookIdentityService.SyncResult sync(@PathVariable UUID workspaceId, @PathVariable String facebookUserId) {
        access.admin(workspaceId);
        if (facebookUserId.isBlank() || facebookUserId.length() > 128) throw new ApiException(HttpStatus.BAD_REQUEST, "Tài khoản Facebook không hợp lệ.");
        return service.sync(workspaceId, facebookUserId);
    }
}
