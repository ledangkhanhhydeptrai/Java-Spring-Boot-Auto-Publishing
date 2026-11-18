package com.socialhub.accountvault.service;

import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AccountVaultService {
    private final JdbcTemplate jdbc;
    private final AccessService access;

    public AccountVaultService(JdbcTemplate jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public record AccountView(UUID id, String platform, String externalAccountId,
                              String displayName, String status, OffsetDateTime tokenExpiresAt,
                              OffsetDateTime lastConnectedAt, OffsetDateTime createdAt) {}

    public record AccountPage(int page, int size, long totalElements, int totalPages,
                              List<AccountView> items) {}

    public record GroupView(UUID id, String name, String description, long accountCount,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record GroupInput(String name, String description) {}

    private static final org.springframework.jdbc.core.RowMapper<AccountView> ACCOUNT_MAPPER =
        (rs, i) -> new AccountView(
            rs.getObject("id", UUID.class), rs.getString("platform"),
            rs.getString("external_account_id"), rs.getString("display_name"),
            rs.getString("status"), rs.getObject("token_expires_at", OffsetDateTime.class),
            rs.getObject("last_connected_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class)
        );

    private static final org.springframework.jdbc.core.RowMapper<GroupView> GROUP_MAPPER =
        (rs, i) -> new GroupView(rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getString("description"), rs.getLong("account_count"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    @Transactional(readOnly = true)
    public AccountPage accounts(UUID workspaceId, int page, int size, String platform,
                                String status, String query) {
        access.workspace(workspaceId);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new ApiException(HttpStatus.BAD_REQUEST, "page/size out of range; size must be 1..100");

        StringBuilder where = new StringBuilder(" WHERE a.workspace_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(workspaceId);

        if (platform != null && !platform.isBlank()) {
            String p = platform.trim().toUpperCase(Locale.ROOT);
            if (!List.of("FACEBOOK_PAGE", "THREADS").contains(p))
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported platform");
            where.append(" AND a.platform = ?");
            args.add(p);
        }
        if (status != null && !status.isBlank()) {
            if (status.length() > 40)
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid status filter");
            where.append(" AND a.status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (query != null && !query.isBlank()) {
            if (query.length() > 100)
                throw new ApiException(HttpStatus.BAD_REQUEST, "Search query is too long");
            // Use strpos instead of LIKE to treat user input as literal (no wildcard semantics).
            where.append(" AND (strpos(lower(a.display_name), ?) > 0 OR strpos(lower(a.external_account_id), ?) > 0)");
            String q = query.trim().toLowerCase(Locale.ROOT);
            args.add(q);
            args.add(q);
        }

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM social_accounts a" + where,
                                        Long.class, args.toArray());
        long total = count == null ? 0 : count;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<AccountView> items = jdbc.query("""
                SELECT a.id, a.platform, a.external_account_id, a.display_name, a.status,
                       a.token_expires_at, a.last_connected_at, a.created_at
                FROM social_accounts a
                """ + where + " ORDER BY a.created_at DESC NULLS LAST, a.id DESC LIMIT ? OFFSET ?",
                ACCOUNT_MAPPER, pageArgs.toArray());
        int totalPages = (int) ((total + size - 1) / size);
        return new AccountPage(page, size, total, totalPages, items);
    }

    @Transactional(readOnly = true)
    public AccountView account(UUID workspaceId, UUID accountId) {
        access.workspace(workspaceId);
        List<AccountView> found = jdbc.query("""
                SELECT id, platform, external_account_id, display_name, status,
                       token_expires_at, last_connected_at, created_at
                FROM social_accounts WHERE id=? AND workspace_id=?
                """, ACCOUNT_MAPPER, accountId, workspaceId);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Account not found");
        return found.getFirst();
    }

    @Transactional(readOnly = true)
    public List<GroupView> groups(UUID workspaceId) {
        access.workspace(workspaceId);
        return jdbc.query("""
            SELECT g.id, g.name, g.description, g.created_at, g.updated_at,
                   COUNT(m.social_account_id) AS account_count
            FROM account_vault_groups g
            LEFT JOIN account_vault_group_members m ON m.group_id=g.id
            WHERE g.workspace_id=?
            GROUP BY g.id, g.name, g.description, g.created_at, g.updated_at
            ORDER BY LOWER(g.name), g.id
            """, GROUP_MAPPER, workspaceId);
    }

    @Transactional
    public GroupView createGroup(UUID workspaceId, GroupInput input) {
        access.admin(workspaceId);
        GroupInput valid = validate(input);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO account_vault_groups (id,workspace_id,name,description) VALUES (?,?,?,?)",
                id, workspaceId, valid.name(), valid.description());
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "Group name already exists or data is invalid");
        }
        return requireGroup(workspaceId, id);
    }

    @Transactional
    public GroupView updateGroup(UUID workspaceId, UUID groupId, GroupInput input) {
        access.admin(workspaceId);
        GroupInput valid = validate(input);
        try {
            int changed = jdbc.update("""
                UPDATE account_vault_groups SET name=?, description=?, updated_at=NOW()
                WHERE id=? AND workspace_id=?
                """, valid.name(), valid.description(), groupId, workspaceId);
            if (changed == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Group not found");
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "Group name already exists or data is invalid");
        }
        return requireGroup(workspaceId, groupId);
    }

    @Transactional
    public void deleteGroup(UUID workspaceId, UUID groupId) {
        access.admin(workspaceId);
        int changed = jdbc.update("DELETE FROM account_vault_groups WHERE id=? AND workspace_id=?",
            groupId, workspaceId);
        if (changed == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Group not found");
        // CASCADE removes only vault group memberships, never real social accounts or jobs.
    }

    @Transactional(readOnly = true)
    public List<AccountView> groupAccounts(UUID workspaceId, UUID groupId) {
        access.workspace(workspaceId);
        requireGroup(workspaceId, groupId);
        return jdbc.query("""
            SELECT a.id, a.platform, a.external_account_id, a.display_name, a.status,
                   a.token_expires_at, a.last_connected_at, a.created_at
            FROM account_vault_group_members m
            JOIN social_accounts a ON a.id=m.social_account_id
            JOIN account_vault_groups g ON g.id=m.group_id
            WHERE g.id=? AND g.workspace_id=? AND a.workspace_id=?
            ORDER BY a.display_name, a.id
            """, ACCOUNT_MAPPER, groupId, workspaceId, workspaceId);
    }

    @Transactional
    public void addAccount(UUID workspaceId, UUID groupId, UUID accountId) {
        access.admin(workspaceId);
        requireGroup(workspaceId, groupId);
        account(workspaceId, accountId); // cross-workspace guard
        jdbc.update("""
            INSERT INTO account_vault_group_members (group_id,social_account_id)
            VALUES (?,?) ON CONFLICT (group_id,social_account_id) DO NOTHING
            """, groupId, accountId);
    }

    @Transactional
    public void removeAccount(UUID workspaceId, UUID groupId, UUID accountId) {
        access.admin(workspaceId);
        requireGroup(workspaceId, groupId);
        jdbc.update("DELETE FROM account_vault_group_members WHERE group_id=? AND social_account_id=?",
            groupId, accountId);
    }

    private GroupView requireGroup(UUID workspaceId, UUID groupId) {
        List<GroupView> found = jdbc.query("""
            SELECT g.id, g.name, g.description, g.created_at, g.updated_at,
                   COUNT(m.social_account_id) AS account_count
            FROM account_vault_groups g
            LEFT JOIN account_vault_group_members m ON m.group_id=g.id
            WHERE g.id=? AND g.workspace_id=?
            GROUP BY g.id, g.name, g.description, g.created_at, g.updated_at
            """, GROUP_MAPPER, groupId, workspaceId);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Group not found");
        return found.getFirst();
    }

    private GroupInput validate(GroupInput input) {
        if (input == null || input.name() == null || input.name().trim().isBlank()
            || input.name().trim().length() > 120
            || (input.description() != null && input.description().length() > 500))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Group name must be 1..120 chars; description <= 500");
        String name = input.name().trim();
        String desc = input.description() == null || input.description().isBlank() ? null : input.description().trim();
        return new GroupInput(name, desc);
    }
}
