package com.socialhub.accountvault.facebook;

import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-only directory of existing Facebook Page authorizations, scoped to one workspace.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/account-vault/facebook")
public class FacebookVaultController {
    private final JdbcTemplate jdbc;
    private final AccessService access;

    public FacebookVaultController(JdbcTemplate jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public record FacebookAccount(String facebookUserId, String examplePageName, String displayName,
                                  long pageCount, long usablePageCount,
                                  OffsetDateTime lastConnectedAt) {
    }

    public record Directory(long facebookAccountCount, long fanpageCount, long facebookGroupCount,
                            int page, int size, long totalPages, List<FacebookAccount> items) {
    }

    public record PageView(UUID id, UUID connectionId, String displayName, String pageStatus,
                           String connectionStatus, boolean tokenUsable, OffsetDateTime lastConnectedAt) {
    }

    public record Pages(int page, int size, long totalElements, long totalPages, List<PageView> items) {
    }

    private static final String FROM = """
            FROM facebook_page_connections c
            JOIN social_accounts a ON a.id = c.social_account_id
            WHERE a.workspace_id = ? AND a.platform = 'FACEBOOK_PAGE'
            """;
    // Stored connection eligibility, not a live Meta health check.
    private static final String USABLE = """
            (a.status = 'CONNECTED' AND c.status = 'CONNECTED'
             AND c.access_token_encrypted IS NOT NULL AND btrim(c.access_token_encrypted) <> ''
             AND (c.token_expires_at IS NULL OR c.token_expires_at > CURRENT_TIMESTAMP))
            """;

    @GetMapping("/accounts")
    @Transactional(readOnly = true)
    public Directory directory(@PathVariable UUID workspaceId,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        access.admin(workspaceId); // same access level as the existing Page connection API
        validate(page, size);
        long people = count("SELECT COUNT(DISTINCT c.facebook_user_id) " + FROM, workspaceId);
        long pages = count("SELECT COUNT(*) FROM social_accounts WHERE workspace_id = ? AND platform = 'FACEBOOK_PAGE'", workspaceId);
        long groups = count("SELECT COUNT(*) FROM facebook_groups WHERE workspace_id = ?", workspaceId);
        List<FacebookAccount> items = jdbc.query("""
                        SELECT c.facebook_user_id, MIN(a.display_name) AS example_page_name,
                               (SELECT i.display_name FROM facebook_authorized_identities i WHERE i.workspace_id = a.workspace_id AND i.facebook_user_id = c.facebook_user_id) AS display_name,
                               COUNT(*) AS page_count,
                               COUNT(*) FILTER (WHERE
                        """ + USABLE + ") AS usable_page_count, MAX(c.last_connected_at) AS last_connected_at "
                        + FROM + " GROUP BY a.workspace_id, c.facebook_user_id ORDER BY c.facebook_user_id LIMIT ? OFFSET ?",
                (rs, row) -> new FacebookAccount(rs.getString("facebook_user_id"),
                        rs.getString("example_page_name"), rs.getString("display_name"), rs.getLong("page_count"),
                        rs.getLong("usable_page_count"), rs.getObject("last_connected_at", OffsetDateTime.class)),
                workspaceId, size, (long) page * size);
        return new Directory(people, pages, groups, page, size, totalPages(people, size), items);
    }

    @GetMapping("/pages")
    @Transactional(readOnly = true)
    public Pages pages(@PathVariable UUID workspaceId, @RequestParam String facebookUserId,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size) {
        access.admin(workspaceId);
        validate(page, size);
        if (facebookUserId == null || facebookUserId.isBlank() || facebookUserId.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Không tìm thấy tài khoản Facebook hợp lệ.");
        }
        String scoped = FROM + " AND c.facebook_user_id = ? ";
        long total = count("SELECT COUNT(*) " + scoped, workspaceId, facebookUserId);
        if (total == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản Facebook trong không gian làm việc này.");
        }
        List<PageView> items = jdbc.query("""
                        SELECT a.id, c.id AS connection_id, a.display_name, a.status AS page_status,
                               c.status AS connection_status, c.last_connected_at,
                        """ + USABLE + " AS token_usable " + scoped + " ORDER BY a.display_name, a.id LIMIT ? OFFSET ?",
                (rs, row) -> new PageView(rs.getObject("id", UUID.class), rs.getObject("connection_id", UUID.class), rs.getString("display_name"),
                        rs.getString("page_status"), rs.getString("connection_status"), rs.getBoolean("token_usable"),
                        rs.getObject("last_connected_at", OffsetDateTime.class)),
                workspaceId, facebookUserId, size, (long) page * size);
        return new Pages(page, size, total, totalPages(total, size), items);
    }

    private long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private static long totalPages(long count, int size) {
        return count / size + (count % size == 0 ? 0 : 1);
    }

    private static void validate(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Số trang hoặc số bản ghi mỗi trang không hợp lệ (tối đa 100).");
        }
    }
}
