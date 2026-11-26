package com.socialhub.googlesheets.service;

import com.socialhub.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Links only explicitly selected imports to a synced Sheet. Does not publish or create jobs. */
@Service
@RequiredArgsConstructor
public class GoogleSheetsImportLinkService {
    private final JdbcTemplate db;

    public record LinkedImport(UUID importId, String fileName, int importedRows) {}
    public record Report(UUID connectionId, int linkedImports, long linkedRows,
                         List<LinkedImport> imports) {}

    /** Call after import rows have been persisted (and flushed if using JPA). */
    @Transactional
    public Report linkImported(UUID workspaceId, UUID connectionId, List<UUID> importIds) {
        if (importIds == null || importIds.isEmpty() || importIds.stream().anyMatch(id -> id == null)
                || importIds.stream().distinct().count() != importIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unique importIds are required");
        }
        var params = new MapSqlParameterSource()
                .addValue("workspace", workspaceId)
                .addValue("connection", connectionId)
                .addValue("ids", importIds);
        var sql = new NamedParameterJdbcTemplate(db);

        // Serialize concurrent link changes for this connection.
        var connections = sql.query("""
                SELECT id FROM google_sheet_connections
                WHERE id = :connection AND workspace_id = :workspace
                  AND sync_status = 'SYNCED' AND writeback_enabled = TRUE
                FOR UPDATE
                """, params, (rs, n) -> rs.getObject(1, UUID.class));
        if (connections.size() != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Sync the selected Sheet and enable writeback first");
        }
        // Refuse a second active writer to the same tab.
        Boolean otherWriter = sql.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM google_sheet_connections c
                    JOIN google_sheet_connections other
                      ON other.spreadsheet_id = c.spreadsheet_id AND other.tab_name = c.tab_name
                     AND other.id <> c.id
                    JOIN google_sheet_import_links l ON l.connection_id = other.id
                    WHERE c.id = :connection AND other.writeback_enabled = TRUE AND l.enabled = TRUE
                )
                """, params, Boolean.class);
        if (Boolean.TRUE.equals(otherWriter)) {
            throw new ApiException(HttpStatus.CONFLICT, "Another active connection writes to the same Sheet tab");
        }

        List<LinkedImport> imports = sql.query("""
                SELECT id, file_name, imported_rows FROM excel_imports
                WHERE workspace_id = :workspace AND id IN (:ids)
                  AND social_account_id IS NOT NULL AND imported_rows > 0
                ORDER BY id
                """, params, (rs, n) -> new LinkedImport(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getInt(3)));
        if (imports.size() != importIds.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "One or more imports are missing, empty, or outside this workspace");
        }
        long expected = imports.stream().mapToLong(LinkedImport::importedRows).sum();
        // Local Excel row numbers are NOT Sheet row numbers. Require exactly one Sheet
        // row per imported post, and a one-to-one mapping across all selected imports.
        // Group by the actual import row, including missing posts, so missing rows fail closed.
        record Counts(long rows, long uniqueMatches, long distinctRows, long importsWithRows) {}
        Counts counts = sql.queryForObject("""
                WITH matched AS (
                    SELECT e.id AS import_id, r.source_row_number, r.post_id,
                           COUNT(s.source_row_number) AS matches,
                           MIN(s.source_row_number) AS sheet_row
                    FROM excel_imports e
                    JOIN excel_import_rows r ON r.import_id = e.id
                    LEFT JOIN posts p ON p.id = r.post_id AND p.workspace_id = e.workspace_id
                    LEFT JOIN google_sheet_staged_rows s
                      ON s.connection_id = :connection AND s.present_in_sheet = TRUE
                     AND s.content = p.content AND p.content IS NOT NULL AND btrim(p.content) <> ''
                     AND s.source_row_number BETWEEN 2 AND 10001
                    WHERE e.workspace_id = :workspace AND e.id IN (:ids)
                    GROUP BY e.id, r.source_row_number, r.post_id
                )
                SELECT COUNT(*) AS rows,
                       COUNT(*) FILTER (WHERE matches = 1) AS unique_matches,
                       COUNT(DISTINCT sheet_row) FILTER (WHERE matches = 1) AS distinct_rows,
                       COUNT(DISTINCT import_id) AS imports_with_rows
                FROM matched
                """, params, (rs, n) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
        if (counts == null || counts.rows() != expected || counts.uniqueMatches() != expected
                || counts.distinctRows() != expected || counts.importsWithRows() != imports.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "Sheet mapping is incomplete or ambiguous; no links created");
        }
        for (LinkedImport item : imports) {
            Long actual = db.queryForObject("SELECT COUNT(*) FROM excel_import_rows WHERE import_id = ?",
                    Long.class, item.importId());
            if (actual == null || actual != item.importedRows()) {
                throw new ApiException(HttpStatus.CONFLICT, "Import row count changed: " + item.importId());
            }
        }
        // Refuse ambiguous matches against previously enabled imports outside this request.
        Boolean overlap = sql.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM excel_import_rows selected_row
                    JOIN posts selected_post ON selected_post.id = selected_row.post_id
                      AND selected_post.workspace_id = :workspace
                    JOIN google_sheet_import_links other_link
                      ON other_link.connection_id = :connection AND other_link.workspace_id = :workspace
                     AND other_link.enabled = TRUE AND other_link.import_id NOT IN (:ids)
                    JOIN excel_import_rows other_row ON other_row.import_id = other_link.import_id
                    JOIN posts other_post ON other_post.id = other_row.post_id
                      AND other_post.workspace_id = :workspace AND other_post.content = selected_post.content
                    WHERE selected_row.import_id IN (:ids)
                )
                """, params, Boolean.class);
        if (Boolean.TRUE.equals(overlap)) {
            throw new ApiException(HttpStatus.CONFLICT, "Selected posts overlap another enabled import");
        }
        for (LinkedImport item : imports) {
            db.update("""
                    INSERT INTO google_sheet_import_links(workspace_id, connection_id, import_id, enabled)
                    VALUES (?, ?, ?, TRUE) ON CONFLICT DO NOTHING
                    """, workspaceId, connectionId, item.importId());
            int updated = db.update("""
                    UPDATE google_sheet_import_links SET enabled = TRUE, updated_at = now()
                    WHERE workspace_id = ? AND connection_id = ? AND import_id = ?
                    """, workspaceId, connectionId, item.importId());
            if (updated != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "Could not establish a unique import link");
            }
        }
        return new Report(connectionId, imports.size(), expected, List.copyOf(imports));
    }
}
