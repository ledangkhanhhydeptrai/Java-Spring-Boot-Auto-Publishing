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
        this.jdbc = jdbc;
        this.access = access;
        this.service = service;
    }

    public record Identity(String facebookUserId, String displayName, String examplePageName,
                           String status, long pageCount, OffsetDateTime lastSyncedAt,
                           OffsetDateTime lastCheckedAt, String healthMessage, long blockedPages, long waitingJobs) {
    }

    public record Directory(long totalElements, long totalPages, List<Identity> items) {
    }

    // Shared by the directory and the global alert summary. Never treat a network error as revoked access.
    static final String EFFECTIVE_STATUS = """
            CASE WHEN i.health_status = 'DISCONNECTED' THEN 'DISCONNECTED'
                 WHEN i.health_status = 'REAUTH_REQUIRED' OR i.user_token_encrypted IS NULL THEN 'REAUTH_REQUIRED'
                 WHEN i.token_expires_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                 WHEN i.health_status = 'CHECK_ERROR' THEN 'CHECK_ERROR'
                 ELSE 'STORED' END
            """;
    private static final String FILTER = """
            (? = 'ALL' OR (? = 'NEEDS_REAUTH' AND status IN ('EXPIRED','REAUTH_REQUIRED')) OR status = ?)
            """;

    public enum StatusFilter {ALL, NEEDS_REAUTH, CHECK_ERROR, DISCONNECTED, STORED}

    @GetMapping
    public Directory list(@PathVariable UUID workspaceId, @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "20") int size,
                          @RequestParam(defaultValue = "ALL") StatusFilter statusFilter) {
        access.admin(workspaceId);
        if (page < 0 || size < 1 || size > 100)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Thông tin phân trang không hợp lệ.");
        String filter = statusFilter.name();
        String scoped = "SELECT i.*, " + EFFECTIVE_STATUS + " AS status FROM facebook_authorized_identities i WHERE i.workspace_id = ?";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + scoped + ") scoped WHERE " + FILTER,
                Long.class, workspaceId, filter, filter, filter);
        long count = total == null ? 0 : total;
        List<Identity> items = jdbc.query("""
                SELECT i.facebook_user_id, i.display_name, p.example_page_name, COALESCE(p.page_count, 0) AS page_count,
                    i.last_synced_at, i.last_checked_at, i.health_message, COALESCE(p.blocked_pages, 0) AS blocked_pages,
                    COALESCE(p.waiting_jobs, 0) AS waiting_jobs,
                    i.status
                FROM (SELECT * FROM ( %s ) scoped WHERE %s ORDER BY facebook_user_id LIMIT ? OFFSET ?) i
                LEFT JOIN LATERAL (
                    SELECT MIN(a.display_name) AS example_page_name, COUNT(*) AS page_count,
                        COUNT(*) FILTER (WHERE c.status <> 'CONNECTED') AS blocked_pages,
                        COALESCE(SUM((SELECT COUNT(*) FROM publishing_jobs j WHERE j.facebook_connection_id = c.id
                            AND j.status IN ('PENDING','RETRY','FAILED') AND c.status <> 'CONNECTED')), 0) AS waiting_jobs
                    FROM facebook_page_connections c JOIN social_accounts a ON a.id = c.social_account_id
                    WHERE a.workspace_id = i.workspace_id AND a.platform = 'FACEBOOK_PAGE'
                      AND c.facebook_user_id = i.facebook_user_id
                ) p ON TRUE
                ORDER BY i.facebook_user_id
                """.formatted(scoped, FILTER), (rs, n) -> new Identity(rs.getString("facebook_user_id"), rs.getString("display_name"),
                rs.getString("example_page_name"), rs.getString("status"), rs.getLong("page_count"),
                rs.getObject("last_synced_at", OffsetDateTime.class), rs.getObject("last_checked_at", OffsetDateTime.class),
                rs.getString("health_message"), rs.getLong("blocked_pages"), rs.getLong("waiting_jobs")), workspaceId, filter, filter, filter, size, (long) page * size);
        return new Directory(count, (count + size - 1) / size, items);
    }

    @PostMapping("/{facebookUserId}/disconnect")
    public FacebookIdentityService.DisconnectResult disconnect(@PathVariable UUID workspaceId, @PathVariable String facebookUserId) {
        access.admin(workspaceId);
        if (facebookUserId.isBlank() || facebookUserId.length() > 128)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tài khoản Facebook không hợp lệ.");
        return service.disconnect(workspaceId, facebookUserId);
    }

    @PostMapping("/{facebookUserId}/sync")
    public FacebookIdentityService.SyncResult sync(@PathVariable UUID workspaceId, @PathVariable String facebookUserId) {
        access.admin(workspaceId);
        if (facebookUserId.isBlank() || facebookUserId.length() > 128)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tài khoản Facebook không hợp lệ.");
        return service.sync(workspaceId, facebookUserId);
    }
}
