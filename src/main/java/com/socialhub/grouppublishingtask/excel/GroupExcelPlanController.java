package com.socialhub.grouppublishingtask.excel;

import com.socialhub.common.ApiException;
import com.socialhub.facebookgroup.entity.FacebookGroup;
import com.socialhub.facebookgroup.repository.FacebookGroupRepository;
import com.socialhub.grouppublishingtask.entity.GroupPublishingTask;
import com.socialhub.grouppublishingtask.repository.GroupPublishingTaskRepository;
import com.socialhub.post.repository.PostRepository;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Controlled Excel -> Group Task planner. NEVER publishes, claims, or retries a task.
 * Preview first; confirm needs matching token and explicit confirmation.
 * Uses the same JPA create semantics as GroupPublishingTaskController.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/excel-imports/{importId}/group-plan")
public class GroupExcelPlanController {
    private static final int MAX_ROWS_PER_CONFIRM = 10; // Start small for the one-task Edge Pilot.
    private final AccessService access;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final GroupPublishingTaskRepository tasks;
    private final FacebookGroupRepository groups;
    private final PostRepository posts;

    public record PlanRequest(UUID facebookGroupId, Integer fromRow, Integer toRow,
                              Integer limit, OffsetDateTime startAt, Integer intervalMinutes,
                              String previewToken, Boolean confirmPlan) { }
    public record PlanRow(int sourceRowNumber, UUID postId, String action,
                          OffsetDateTime scheduledAt, String reason) { }
    public record PlanResult(UUID importId, UUID facebookGroupId, boolean dryRun,
                             String previewToken, int readyToCreate, int created,
                             int alreadyExists, int blocked, List<PlanRow> rows) { }
    private record Source(int sourceRowNumber, UUID postId, String content, String postStatus,
                          String approvalStatus, String processingStatus, int mediaCount,
                          boolean taskExists, boolean sheetChanged) { }
    private record Snapshot(List<PlanRow> rows, String fingerprint) { }

    @PostMapping("/preview")
    public PlanResult preview(@PathVariable UUID workspaceId, @PathVariable UUID importId,
                              @RequestBody PlanRequest request) {
        access.admin(workspaceId);
        validate(workspaceId, importId, request);
        Snapshot snapshot = snapshot(workspaceId, importId, request);
        return report(importId, request.facebookGroupId(), snapshot, true, 0, snapshot.rows());
    }

    @PostMapping("/confirm")
    public PlanResult confirm(@PathVariable UUID workspaceId, @PathVariable UUID importId,
                              @RequestBody PlanRequest request) {
        access.admin(workspaceId);
        validate(workspaceId, importId, request);
        if (!Boolean.TRUE.equals(request.confirmPlan()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "confirmPlan=true required");
        if (request.previewToken() == null || request.previewToken().isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "Preview token required");

        return tx.execute(transaction -> {
            // Serialize concurrent Group plans for this target group in this transaction.
            db.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))",
                    (rs, n) -> rs.getObject(1), importId.toString(), request.facebookGroupId().toString());
            Snapshot snapshot = snapshot(workspaceId, importId, request);
            if (!MessageDigest.isEqual(snapshot.fingerprint().getBytes(StandardCharsets.UTF_8),
                    request.previewToken().getBytes(StandardCharsets.UTF_8)))
                throw new ApiException(HttpStatus.CONFLICT, "Import or task state changed; preview again");

            FacebookGroup group = groups.findById(request.facebookGroupId()).orElseThrow(
                    () -> new ApiException(HttpStatus.NOT_FOUND, "Group not found"));
            int created = 0;
            List<PlanRow> after = new ArrayList<>();
            for (PlanRow row : snapshot.rows()) {
                if (!"READY_TO_CREATE".equals(row.action())) {
                    after.add(row);
                    continue;
                }
                Boolean exists = db.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM group_publishing_tasks
                        WHERE post_id=? AND facebook_group_id=?)
                        """, Boolean.class, row.postId(), request.facebookGroupId());
                if (Boolean.TRUE.equals(exists)) {
                    // Fail the complete confirmation. We do not create a partial plan.
                    throw new ApiException(HttpStatus.CONFLICT, "Another Group Task was added; preview again");
                }
                var post = posts.findById(row.postId()).orElseThrow(
                        () -> new ApiException(HttpStatus.CONFLICT, "Post disappeared; preview again"));
                if (!post.workspace.id.equals(workspaceId))
                    throw new ApiException(HttpStatus.CONFLICT, "Post workspace changed");
                GroupPublishingTask task = new GroupPublishingTask();
                task.post = post;
                task.facebookGroup = group;
                task.scheduledAt = row.scheduledAt();
                // Do NOT force READY/PROCESSING. The existing entity default and
                // /api/posts/{postId}/group-tasks/{id}/ready approval flow are retained.
                tasks.save(task);
                created++;
                after.add(new PlanRow(row.sourceRowNumber(), row.postId(), "TASK_CREATED",
                        row.scheduledAt(), "Created with existing Group Task approval semantics"));
            }
            return report(importId, request.facebookGroupId(), snapshot, false, created, after);
        });
    }

    private void validate(UUID workspaceId, UUID importId, PlanRequest req) {
        if (req == null || req.facebookGroupId() == null || req.startAt() == null ||
                req.fromRow() == null || req.toRow() == null || req.limit() == null ||
                req.intervalMinutes() == null || req.fromRow() < 2 ||
                req.toRow() < req.fromRow() || req.toRow() > 10001 ||
                req.limit() < 1 || req.limit() > MAX_ROWS_PER_CONFIRM ||
                req.intervalMinutes() < 5 || req.intervalMinutes() > 1440 ||
                !req.startAt().toInstant().isAfter(Instant.now().plusSeconds(300))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "facebookGroupId,startAt(>5min),fromRow,toRow,limit(1..10),intervalMinutes(5..1440) required");
        }
        Boolean valid = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM excel_imports WHERE id=? AND workspace_id=?)
                """, Boolean.class, importId, workspaceId);
        if (!Boolean.TRUE.equals(valid))
            throw new ApiException(HttpStatus.NOT_FOUND, "Excel import not found");
        Boolean groupValid = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM facebook_groups
                  WHERE id=? AND workspace_id=? AND group_url LIKE 'https://%facebook.com/groups/%')
                """, Boolean.class, req.facebookGroupId(), workspaceId);
        if (!Boolean.TRUE.equals(groupValid))
            throw new ApiException(HttpStatus.CONFLICT, "Target Group not in workspace / invalid URL");
    }

    private Snapshot snapshot(UUID workspaceId, UUID importId, PlanRequest req) {
        List<Source> sourceRows = db.query("""
                SELECT r.source_row_number, r.post_id, p.content, p.status AS post_status,
                       p.approval_status, r.processing_status,
                       (SELECT count(*) FROM post_media m WHERE m.post_id=p.id) AS media_count,
                       EXISTS(SELECT 1 FROM group_publishing_tasks t
                           WHERE t.post_id=p.id AND t.facebook_group_id=?) AS task_exists,
                       EXISTS(SELECT 1 FROM google_sheet_import_links l
                           JOIN google_sheet_staged_rows s
                             ON s.connection_id=l.connection_id
                            AND s.source_row_number=r.source_row_number
                           WHERE l.import_id=r.import_id AND l.workspace_id=e.workspace_id
                             AND l.enabled=TRUE AND s.present_in_sheet=TRUE
                             AND s.content<>p.content) AS sheet_changed
                FROM excel_import_rows r
                JOIN excel_imports e ON e.id=r.import_id AND e.workspace_id=?
                JOIN posts p ON p.id=r.post_id AND p.workspace_id=e.workspace_id
                WHERE r.import_id=? AND r.post_id IS NOT NULL
                  AND r.source_row_number BETWEEN ? AND ?
                ORDER BY r.source_row_number, r.id
                LIMIT ?
                """, (rs, n) -> new Source(rs.getInt("source_row_number"),
                rs.getObject("post_id", UUID.class), rs.getString("content"),
                rs.getString("post_status"), rs.getString("approval_status"),
                rs.getString("processing_status"), rs.getInt("media_count"),
                rs.getBoolean("task_exists"), rs.getBoolean("sheet_changed")),
                req.facebookGroupId(), workspaceId, importId, req.fromRow(), req.toRow(), req.limit());

        List<PlanRow> rows = new ArrayList<>();
        int n = 0;
        for (Source src : sourceRows) {
            OffsetDateTime scheduled = req.startAt().plusMinutes((long) n * req.intervalMinutes());
            n++;
            String action = "READY_TO_CREATE", reason = "Text post, approved, no existing Group Task";
            if (src.taskExists()) { action = "ALREADY_EXISTS"; reason = "Existing task for this Post/Group"; }
            else if (src.processingStatus() != null && src.processingStatus().startsWith("SKIPPED")) {
                action = "BLOCKED"; reason = "Skipped Excel source row";
            } else if (!"APPROVED".equals(src.approvalStatus()) ||
                    !("READY".equals(src.postStatus()) || "SCHEDULED".equals(src.postStatus()))) {
                action = "BLOCKED"; reason = "Post not approved/ready";
            } else if (src.mediaCount() > 0) {
                action = "BLOCKED"; reason = "Text-only Edge Group Pilot does not support media";
            } else if (src.sheetChanged()) {
                action = "BLOCKED"; reason = "Current linked Sheet content differs from imported Post";
            } else if (src.content() == null || src.content().isBlank()) {
                action = "BLOCKED"; reason = "Post content empty";
            }
            rows.add(new PlanRow(src.sourceRowNumber(), src.postId(), action, scheduled, reason));
        }
        String fingerprint = fingerprint(importId, req, sourceRows, rows);
        return new Snapshot(List.copyOf(rows), fingerprint);
    }

    private static String fingerprint(UUID importId, PlanRequest req,
                                      List<Source> sources, List<PlanRow> rows) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            StringBuilder raw = new StringBuilder(importId.toString()).append('|')
                    .append(req.facebookGroupId()).append('|').append(req.fromRow()).append('|')
                    .append(req.toRow()).append('|').append(req.limit()).append('|')
                    .append(req.startAt().toInstant()).append('|').append(req.intervalMinutes());
            for (int i = 0; i < sources.size(); i++) {
                Source s = sources.get(i);
                PlanRow r = rows.get(i);
                raw.append('|').append(s.sourceRowNumber()).append(':').append(s.postId())
                        .append(':').append(s.content()).append(':').append(s.postStatus())
                        .append(':').append(s.approvalStatus()).append(':').append(s.mediaCount())
                        .append(':').append(s.taskExists()).append(':').append(s.sheetChanged())
                        .append(':').append(r.action()).append(':').append(r.scheduledAt());
            }
            return HexFormat.of().formatHex(md.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static PlanResult report(UUID importId, UUID groupId, Snapshot snapshot,
                                     boolean dryRun, int created, List<PlanRow> rows) {
        int eligible = 0, existing = 0, blocked = 0;
        for (PlanRow row : snapshot.rows()) {
            switch (row.action()) {
                case "READY_TO_CREATE" -> eligible++;
                case "ALREADY_EXISTS" -> existing++;
                default -> blocked++;
            }
        }
        return new PlanResult(importId, groupId, dryRun, snapshot.fingerprint(),
                eligible, created, existing, blocked, List.copyOf(rows));
    }
}
