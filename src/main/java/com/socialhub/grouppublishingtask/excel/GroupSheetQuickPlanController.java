package com.socialhub.grouppublishingtask.excel;

import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Two-input Google Sheet -> Group Task scheduler. Never claims, approves, or publishes.
 * Requires exactly one linked import and one eligible destination Group in the workspace;
 * ambiguous mapping fails closed instead of silently publishing to the wrong destination.
 * Delegates eligibility, idempotency and atomic Group Task creation to the established
 * GroupExcelPlanController. No schema change or modification to existing flows.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/group-sheet-quick")
public class GroupSheetQuickPlanController {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{20,200}");
    private static final int CHUNK_SIZE = 10; // Existing safe planner restriction.
    private static final int MAX_SOURCE_ROWS = 200; // Cap a single explicit confirmation.
    private static final int FIXED_GAP_MINUTES = 10;

    private final JdbcTemplate db;
    private final AccessService access;
    private final GroupExcelPlanController planner;
    private final GroupSheetWritebackController writeback;
    private final TransactionTemplate transactions;

    public record Request(String sheetUrl, OffsetDateTime startAt, String previewToken, Boolean confirmSchedule) { }
    public record LinkRequest(String sheetUrl, Boolean confirmWrite) { }
    public record Row(int sourceRowNumber, UUID postId, String action, String reason,
                      OffsetDateTime scheduledAt) { }
    public record Result(UUID connectionId, UUID importId, UUID facebookGroupId,
                         String spreadsheetId, String tabName, String groupName,
                         boolean dryRun, int sourceRows, int readyToCreate, int existing,
                         int blocked, int created, int intervalMinutes, String previewToken,
                         List<Row> rows) { }
    public record WritebackResult(UUID connectionId, UUID importId, UUID facebookGroupId,
                                  boolean dryRun, int candidates, int written,
                                  int alreadySynced, int blocked,
                                  List<GroupSheetWritebackController.Row> rows) { }
    private record Match(UUID connectionId, UUID importId, UUID groupId,
                         String spreadsheetId, String tabName, String groupName) { }
    private record Chunk(GroupExcelPlanController.PlanRequest request,
                         GroupExcelPlanController.PlanResult preview) { }
    private record Assembly(Result result, List<Chunk> chunks) { }

    @PostMapping("/preview")
    public Result preview(@PathVariable UUID workspaceId, @RequestBody Request request) {
        access.admin(workspaceId);
        Match match = resolve(workspaceId, request == null ? null : request.sheetUrl());
        validateStart(request);
        return assemble(workspaceId, match, request.startAt()).result();
    }

    @PostMapping("/confirm")
    public Result confirm(@PathVariable UUID workspaceId, @RequestBody Request request) {
        access.admin(workspaceId);
        Match match = resolve(workspaceId, request == null ? null : request.sheetUrl());
        validateStart(request);
        if (!Boolean.TRUE.equals(request.confirmSchedule()) ||
                request.previewToken() == null || request.previewToken().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Confirm preview and supply the exact previewToken; no Group Tasks created");
        }
        // One DB transaction for all batches; any conflict rolls the complete plan back.
        return transactions.execute(tx -> {
            Assembly assembly = assemble(workspaceId, match, request.startAt());
            if (!sameToken(assembly.result().previewToken(), request.previewToken()))
                throw new ApiException(HttpStatus.CONFLICT, "Sheet/Group/Task state changed; preview again");
            int created = 0;
            for (Chunk chunk : assembly.chunks()) {
                if (chunk.preview().readyToCreate() == 0) continue;
                var original = chunk.request();
                var confirm = new GroupExcelPlanController.PlanRequest(
                        original.facebookGroupId(), original.fromRow(), original.toRow(),
                        original.limit(), original.startAt(), original.intervalMinutes(),
                        chunk.preview().previewToken(), true);
                var done = planner.confirm(workspaceId, match.importId(), confirm);
                created += done.created();
            }
            // Prevent accidental live eligibility even if the entity defaults
            // change in a future Backend version. All new tasks must be PENDING.
            for (var chunk : assembly.chunks()) {
                for (var row : chunk.preview().rows()) {
                    if (!"READY_TO_CREATE".equals(row.action())) continue;
                    String status = db.queryForObject("""
                            SELECT status FROM group_publishing_tasks
                             WHERE post_id=? AND facebook_group_id=?
                             ORDER BY created_at DESC LIMIT 1
                            """, String.class, row.postId(), match.groupId());
                    if (!"PENDING".equals(status))
                        throw new ApiException(HttpStatus.CONFLICT,
                                "Unexpected Group Task state; entire plan rolled back. No LIVE approval.");
                }
            }
            var initial = assembly.result();
            return new Result(initial.connectionId(), initial.importId(), initial.facebookGroupId(),
                    initial.spreadsheetId(), initial.tabName(), initial.groupName(), false,
                    initial.sourceRows(), initial.readyToCreate(), initial.existing(),
                    initial.blocked(), created, initial.intervalMinutes(), "", initial.rows());
        });
    }

    @PostMapping("/links/preview")
    public WritebackResult linksPreview(@PathVariable UUID workspaceId, @RequestBody LinkRequest req) {
        access.admin(workspaceId);
        Match m = resolve(workspaceId, req == null ? null : req.sheetUrl());
        return writeback(workspaceId, m, true);
    }

    @PostMapping("/links/write")
    public WritebackResult linksWrite(@PathVariable UUID workspaceId, @RequestBody LinkRequest req) {
        access.admin(workspaceId);
        Match m = resolve(workspaceId, req == null ? null : req.sheetUrl());
        if (!Boolean.TRUE.equals(req.confirmWrite()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "confirmWrite=true required");
        // Each batch is independently verified against Sheet content by the existing service.
        // This is NOT publication; it only writes already PUBLISHED Group permalinks to L.
        return writeback(workspaceId, m, false);
    }

    private WritebackResult writeback(UUID workspaceId, Match m, boolean dryRun) {
        List<Integer> rows = sourceRows(workspaceId, m.importId());
        int candidateCount = 0, written = 0, synced = 0, blocked = 0;
        List<GroupSheetWritebackController.Row> allRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += CHUNK_SIZE) {
            int last = Math.min(i + CHUNK_SIZE, rows.size());
            var req = new GroupSheetWritebackController.Request(
                    m.connectionId(), m.groupId(), rows.get(i), rows.get(last - 1),
                    last - i, !dryRun);
            var res = dryRun ? writeback.preview(workspaceId, m.importId(), req)
                    : writeback.write(workspaceId, m.importId(), req);
            candidateCount += res.candidates();
            written += res.written();
            synced += res.alreadySynced();
            blocked += res.blocked();
            allRows.addAll(res.rows());
        }
        return new WritebackResult(m.connectionId(), m.importId(), m.groupId(),
                dryRun, candidateCount, written, synced, blocked, List.copyOf(allRows));
    }

    private Assembly assemble(UUID workspaceId, Match m, OffsetDateTime startAt) {
        List<Integer> source = sourceRows(workspaceId, m.importId());
        ensureSheetRowsMatch(workspaceId, m, source);
        List<Chunk> chunks = new ArrayList<>();
        List<Row> details = new ArrayList<>();
        StringBuilder hashInput = new StringBuilder(m.connectionId().toString()).append('|')
                .append(m.importId()).append('|').append(m.groupId()).append('|')
                .append(startAt.toInstant()).append('|').append(source.size());
        int ready = 0, existing = 0, blocked = 0;
        for (int i = 0; i < source.size(); i += CHUNK_SIZE) {
            int toIndex = Math.min(i + CHUNK_SIZE, source.size());
            var batch = new GroupExcelPlanController.PlanRequest(
                    m.groupId(), source.get(i), source.get(toIndex - 1), toIndex - i,
                    startAt.plusMinutes((long) i * FIXED_GAP_MINUTES),
                    FIXED_GAP_MINUTES, null, false);
            var checked = planner.preview(workspaceId, m.importId(), batch);
            if (checked.rows().size() != toIndex - i)
                throw new ApiException(HttpStatus.CONFLICT,
                        "Source rows changed or duplicate row mapping found; no tasks created");
            chunks.add(new Chunk(batch, checked));
            hashInput.append('|').append(checked.previewToken());
            ready += checked.readyToCreate();
            existing += checked.alreadyExists();
            blocked += checked.blocked();
            for (var row : checked.rows()) {
                details.add(new Row(row.sourceRowNumber(), row.postId(), row.action(),
                        row.reason(), row.scheduledAt()));
            }
        }
        Result result = new Result(m.connectionId(), m.importId(), m.groupId(),
                m.spreadsheetId(), m.tabName(), m.groupName(), true, source.size(),
                ready, existing, blocked, 0, FIXED_GAP_MINUTES,
                sha256(hashInput.toString()), List.copyOf(details));
        return new Assembly(result, List.copyOf(chunks));
    }

    private List<Integer> sourceRows(UUID workspaceId, UUID importId) {
        List<Integer> rows = db.query("""
                SELECT DISTINCT r.source_row_number
                  FROM excel_import_rows r
                  JOIN excel_imports e ON e.id=r.import_id AND e.workspace_id=?
                 WHERE r.import_id=? AND r.post_id IS NOT NULL
                   AND r.source_row_number BETWEEN 2 AND 10001
                 ORDER BY r.source_row_number
                 LIMIT ?
                """, (rs, n) -> rs.getInt(1), workspaceId, importId, MAX_SOURCE_ROWS + 1);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.CONFLICT,
                "Linked Excel import has no Post rows; nothing scheduled");
        if (rows.size() > MAX_SOURCE_ROWS) throw new ApiException(HttpStatus.CONFLICT,
                "More than 200 source rows: split the import before making a single plan");
        return rows;
    }

    private void ensureSheetRowsMatch(UUID workspaceId, Match match, List<Integer> rows) {
        Integer unmatched = db.queryForObject("""
                SELECT COUNT(*)
                  FROM excel_import_rows r
                  JOIN excel_imports e ON e.id=r.import_id AND e.workspace_id=?
                  JOIN posts p ON p.id=r.post_id AND p.workspace_id=e.workspace_id
                 WHERE r.import_id=? AND r.post_id IS NOT NULL
                   AND r.source_row_number BETWEEN 2 AND 10001
                   AND NOT EXISTS (
                     SELECT 1 FROM google_sheet_staged_rows s
                      WHERE s.connection_id=?
                        AND s.source_row_number=r.source_row_number
                        AND s.present_in_sheet=TRUE AND s.content=p.content
                   )
                """, Integer.class, workspaceId, match.importId(), match.connectionId());
        if (unmatched == null || unmatched > 0)
            throw new ApiException(HttpStatus.CONFLICT,
                    "Some linked Sheet rows are missing or differ from imported Posts (" + unmatched
                    + "); re-sync Google Sheets before planning");
    }

    private Match resolve(UUID workspaceId, String sheetUrl) {
        String id = parseSheetId(sheetUrl);
        List<Match> links = db.query("""
                SELECT c.id, e.id, c.spreadsheet_id, c.tab_name
                  FROM google_sheet_connections c
                  JOIN google_sheet_import_links l
                    ON l.connection_id=c.id AND l.workspace_id=c.workspace_id
                  JOIN excel_imports e ON e.id=l.import_id AND e.workspace_id=c.workspace_id
                 WHERE c.workspace_id=? AND c.spreadsheet_id=?
                   AND c.sync_status='SYNCED' AND l.enabled=TRUE
                """, (rs, n) -> new Match(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), null, rs.getString(3),
                rs.getString(4), null), workspaceId, id);
        if (links.size() != 1) throw new ApiException(HttpStatus.CONFLICT,
                "Google Sheet must have exactly one enabled, SYNCED linked Import in this Workspace (found "
                        + links.size() + "); no tasks created");
        List<Match> groups = db.query("""
                SELECT id, name FROM facebook_groups
                 WHERE workspace_id=? AND group_url LIKE 'https://%facebook.com/groups/%'
                 ORDER BY id
                """, (rs, n) -> new Match(null, null,
                rs.getObject(1, UUID.class), null, null, rs.getString(2)), workspaceId);
        if (groups.size() != 1) throw new ApiException(HttpStatus.CONFLICT,
                "Workspace must have exactly one Facebook Group to auto-select (found "
                        + groups.size() + "); configure a default Group first");
        Match link = links.getFirst();
        Match group = groups.getFirst();
        return new Match(link.connectionId(), link.importId(), group.groupId(),
                id, link.tabName(), group.groupName());
    }

    private static String parseSheetId(String link) {
        if (link == null || link.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "Google Sheets link is required");
        try {
            URI u = URI.create(link.trim());
            if (!"https".equalsIgnoreCase(u.getScheme()) ||
                    !"docs.google.com".equalsIgnoreCase(u.getHost()))
                throw new IllegalArgumentException("Google Sheets HTTPS link required");
            String[] segments = u.getPath().split("/");
            if (segments.length < 4 || !"spreadsheets".equals(segments[1]) ||
                    !"d".equals(segments[2]) || !ID.matcher(segments[3]).matches())
                throw new IllegalArgumentException("Invalid Google Sheet URL path");
            return segments[3];
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Link phải có dạng https://docs.google.com/spreadsheets/d/<id>/edit");
        }
    }

    private static void validateStart(Request req) {
        if (req == null || req.startAt() == null ||
                !req.startAt().toInstant().isAfter(Instant.now().plusSeconds(300)))
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Giờ bắt đầu phải ở tương lai ít nhất 5 phút");
    }
    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean sameToken(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
