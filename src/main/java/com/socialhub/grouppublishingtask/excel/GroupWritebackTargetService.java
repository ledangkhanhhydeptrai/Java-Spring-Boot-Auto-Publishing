package com.socialhub.grouppublishingtask.excel;

import com.socialhub.common.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** Explicit opt-in, one Group per physical Sheet tab. Never selects a Group implicitly. */
@Service
public class GroupWritebackTargetService {
    private final JdbcTemplate db;
    private final GroupSheetQuickPlanController planner;
    public GroupWritebackTargetService(JdbcTemplate db, GroupSheetQuickPlanController planner) {
        this.db = db; this.planner = planner;
    }
    public record Target(UUID connectionId, UUID facebookGroupId, boolean enabled) { }
    public record Selection(UUID connectionId, UUID facebookGroupId, boolean enabled) { }
    private record Identity(String spreadsheetId, String tabName) { }

    @Transactional(readOnly = true)
    public Target get(UUID workspaceId, UUID connectionId) {
        requireConnection(workspaceId, connectionId);
        List<Target> rows = db.query("""
                SELECT b.connection_id,b.facebook_group_id,b.enabled
                  FROM group_sheet_writeback_targets b
                  JOIN google_sheet_connections c ON c.id=b.connection_id AND c.workspace_id=b.workspace_id
                  JOIN facebook_groups g ON g.id=b.facebook_group_id AND g.workspace_id=b.workspace_id
                 WHERE b.workspace_id=? AND b.connection_id=?
                """, (rs,n) -> new Target(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getBoolean(3)),workspaceId,connectionId);
        return rows.isEmpty() ? new Target(connectionId,null,false) : rows.getFirst();
    }

    @Transactional
    public Target save(UUID workspaceId, Selection request) {
        if (request == null || request.connectionId()==null || request.facebookGroupId()==null)
            throw new ApiException(HttpStatus.BAD_REQUEST,"connectionId and facebookGroupId required");
        UUID connectionId=request.connectionId(), groupId=request.facebookGroupId();
        requireConnection(workspaceId,connectionId);
        if (!request.enabled()) {
            int changed=db.update("""
                    UPDATE group_sheet_writeback_targets SET enabled=FALSE,updated_at=NOW()
                    WHERE workspace_id=? AND connection_id=? AND facebook_group_id=?
                    """,workspaceId,connectionId,groupId);
            if (changed!=1) throw new ApiException(HttpStatus.CONFLICT,"No matching Group writeback binding to disable");
            return new Target(connectionId,groupId,false);
        }
        List<Identity> candidates=db.query("""
                SELECT c.spreadsheet_id,c.tab_name
                  FROM google_sheet_connections c
                  JOIN facebook_groups g ON g.workspace_id=c.workspace_id AND g.id=?
                 WHERE c.workspace_id=? AND c.id=? AND c.sync_status='SYNCED'
                   AND c.writeback_enabled=TRUE AND g.external_group_id ~ '^[0-9]+$'
                   AND EXISTS (
                     SELECT 1 FROM google_sheet_import_links l
                     JOIN excel_imports e ON e.id=l.import_id AND e.workspace_id=l.workspace_id
                     JOIN excel_import_rows r ON r.import_id=e.id
                     JOIN posts p ON p.id=r.post_id AND p.workspace_id=e.workspace_id
                     JOIN group_publishing_tasks t ON t.post_id=p.id AND t.facebook_group_id=g.id
                     WHERE l.connection_id=c.id AND l.workspace_id=c.workspace_id AND l.enabled=TRUE
                   )
                """,(rs,n)->new Identity(rs.getString(1),rs.getString(2)),groupId,workspaceId,connectionId);
        if(candidates.size()!=1) throw new ApiException(HttpStatus.CONFLICT,"Select a SYNCED writeback-enabled Sheet with linked Group Tasks in this workspace");
        Identity i=candidates.getFirst();
        try {
            int changed=db.update("""
                    INSERT INTO group_sheet_writeback_targets
                        (connection_id,facebook_group_id,workspace_id,spreadsheet_id,tab_name,enabled)
                    VALUES (?,?,?,?,?,TRUE)
                    ON CONFLICT (connection_id) DO UPDATE SET enabled=TRUE,updated_at=NOW()
                    WHERE group_sheet_writeback_targets.facebook_group_id=EXCLUDED.facebook_group_id
                      AND group_sheet_writeback_targets.workspace_id=EXCLUDED.workspace_id
                      AND group_sheet_writeback_targets.spreadsheet_id=EXCLUDED.spreadsheet_id
                      AND group_sheet_writeback_targets.tab_name=EXCLUDED.tab_name
                    """,connectionId,groupId,workspaceId,i.spreadsheetId(),i.tabName());
            if(changed!=1) throw new ApiException(HttpStatus.CONFLICT,"Column L already bound to another Group or Sheet identity; no reassignment");
        } catch(DataIntegrityViolationException conflict) {
            throw new ApiException(HttpStatus.CONFLICT,"This Sheet tab already has a Group writeback binding, or its target was removed");
        }
        return new Target(connectionId,groupId,true);
    }

    private void requireConnection(UUID workspaceId,UUID connectionId) {
        if(!Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM google_sheet_connections WHERE workspace_id=? AND id=?)
                """,Boolean.class,workspaceId,connectionId)))
            throw new ApiException(HttpStatus.NOT_FOUND,"Sheet connection not found in workspace");
    }

    @Transactional(readOnly=true)
    public List<UUID> nextTargets(UUID after,int limit) {
        return db.query("""
                SELECT b.connection_id FROM group_sheet_writeback_targets b
                JOIN google_sheet_connections c ON c.id=b.connection_id AND c.workspace_id=b.workspace_id
                  AND c.spreadsheet_id=b.spreadsheet_id AND c.tab_name=b.tab_name
                JOIN facebook_groups g ON g.id=b.facebook_group_id AND g.workspace_id=b.workspace_id
                WHERE b.enabled=TRUE AND c.sync_status='SYNCED' AND c.writeback_enabled=TRUE
                  AND (CAST(? AS uuid) IS NULL OR b.connection_id>?)
                  AND EXISTS (
                      SELECT 1 FROM google_sheet_import_links l
                      JOIN excel_imports e ON e.id=l.import_id AND e.workspace_id=l.workspace_id
                      JOIN excel_import_rows r ON r.import_id=e.id
                      JOIN posts p ON p.id=r.post_id AND p.workspace_id=e.workspace_id
                      JOIN group_publishing_tasks t ON t.post_id=p.id AND t.facebook_group_id=g.id
                      WHERE l.connection_id=c.id AND l.workspace_id=c.workspace_id AND l.enabled=TRUE
                        AND t.status='PUBLISHED' AND NULLIF(BTRIM(t.published_url),'') IS NOT NULL
                  )
                ORDER BY b.connection_id LIMIT ?
                """,(rs,n)->rs.getObject(1,UUID.class),after,after,limit);
    }

    /** Row lock serializes automatic writers for this binding across JVMs and config changes.
     * The existing planner still rechecks live Sheet content and permalink before writing. */
    @Transactional
    public GroupSheetQuickPlanController.WritebackResult run(UUID connectionId) {
        List<UUID> groups=db.query("""
                SELECT b.facebook_group_id FROM group_sheet_writeback_targets b
                JOIN google_sheet_connections c ON c.id=b.connection_id AND c.workspace_id=b.workspace_id
                  AND c.spreadsheet_id=b.spreadsheet_id AND c.tab_name=b.tab_name
                JOIN facebook_groups g ON g.id=b.facebook_group_id AND g.workspace_id=b.workspace_id
                WHERE b.connection_id=? AND b.enabled=TRUE AND c.sync_status='SYNCED' AND c.writeback_enabled=TRUE
                FOR UPDATE OF b SKIP LOCKED
                """,(rs,n)->rs.getObject(1,UUID.class),connectionId);
        if(groups.size()!=1) return null;
        return planner.runConfiguredWriteback(connectionId,groups.getFirst());
    }
}
