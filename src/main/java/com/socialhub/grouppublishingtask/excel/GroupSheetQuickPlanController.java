package com.socialhub.grouppublishingtask.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.socialhub.common.ApiException;
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

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Quick UI: exactly two inputs (Sheet URL + start time). Joins current staged
 * Sheet rows to ALL enabled linked Excel imports by exact, unique Post.content.
 * Never guesses by import order or reuses Excel-local row numbers as Sheet rows.
 * Scheduling only creates PENDING Group Tasks. IMAGE-only posts may be
 * scheduled but are NOT approved or automatically published by this Controller.
 * This controller NEVER approves, claims, publishes or retries anything.
 * <p>
 * Group links writeback: verified PUBLISHED permalinks only, exact matching live
 * Google Sheet B text, write only column L. The original Sheet G/H/I/J/K is untouched.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/group-sheet-quick")
public class GroupSheetQuickPlanController {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{20,200}");
    private static final int MAX_ROWS = 1200;
    private static final int GAP_MINUTES = 10;
    private static final int MAX_GROUP_IMAGES_PER_POST = 10; // scheduling only; never auto-publish
    private static final int SHEETS_WRITE_BATCH = 40;
    private static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets";

    private final JdbcTemplate db;
    private final AccessService access;
    private final TransactionTemplate transactions;
    private final GroupPublishingTaskRepository tasks;
    private final FacebookGroupRepository groups;
    private final PostRepository posts;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public record Request(String sheetUrl, OffsetDateTime startAt, String previewToken, Boolean confirmSchedule) {
    }

    public record LinkRequest(String sheetUrl, Boolean confirmWrite) {
    }

    public record Row(int sourceRowNumber, UUID postId, String action, String reason, OffsetDateTime scheduledAt) {
    }

    public record Result(UUID connectionId, UUID importId, UUID facebookGroupId,
                         String spreadsheetId, String tabName, String groupName,
                         boolean dryRun, int sourceRows, int readyToCreate, int existing,
                         int blocked, int created, int intervalMinutes, String previewToken,
                         List<Row> rows) {
    }

    public record WritebackResult(UUID connectionId, UUID importId, UUID facebookGroupId,
                                  boolean dryRun, int candidates, int written,
                                  int alreadySynced, int blocked,
                                  List<GroupSheetWritebackController.Row> rows) {
    }

    private record Connection(UUID id, String spreadsheetId, String tabName,
                              boolean writebackEnabled) {
    }

    private record Group(UUID id, String name, String externalId) {
    }

    private record SheetRow(int row, String content) {
    }

    private record PostCandidate(UUID importId, int excelRow, UUID postId, String content,
                                 String postStatus, String approvalStatus, String processingStatus,
                                 int mediaCount, int imageCount, int videoCount,
                                 int otherCount, int invalidMediaUrlCount,
                                 String mediaFingerprint) {
    }

    private record Mapped(SheetRow sheet, PostCandidate match, boolean ambiguous) {
    }

    private record Source(Connection connection, Group group, List<Mapped> mapped) {
    }

    private record Snapshot(Result result, List<Mapped> mapped) {
    }

    private record Published(int count, String url) {
    }

    // Group scheduling eligibility, not unrelated progress updates from
    // Facebook Pages / Threads. These three states can transition while a
    // user is confirming the preview, but all remain eligible for a PENDING
    // Group Task. Every other state is ineligible.
    private static boolean groupPostEligible(String status) {
        return "READY".equals(status) || "SCHEDULED".equals(status) || "PUBLISHED".equals(status);
    }

    private static boolean importRowSkipped(String status) {
        return status != null && status.startsWith("SKIPPED");
    }

    @PostMapping("/preview")
    public Result preview(@PathVariable UUID workspaceId, @RequestBody Request request) {
        access.admin(workspaceId);
        requireStart(request);
        return snapshot(workspaceId, source(workspaceId, request.sheetUrl()), request.startAt()).result();
    }

    @PostMapping("/confirm")
    public Result confirm(@PathVariable UUID workspaceId, @RequestBody Request request) {
        access.admin(workspaceId);
        requireStart(request);
        if (!Boolean.TRUE.equals(request.confirmSchedule()) ||
                request.previewToken() == null || request.previewToken().isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "Preview token and confirmSchedule=true required");

        return transactions.execute(transaction -> {
            Source src = source(workspaceId, request.sheetUrl());
            // A recent SYNCED cache is not proof that the live Sheet is unchanged.
            // Before any DB write, compare the actual Google Sheet column B.
            verifyLiveSheetContent(src);
            // Ensure two concurrent Quick plans for this group cannot both create tasks.
            db.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?),hashtext(?))",
                    (rs, n) -> rs.getObject(1), workspaceId.toString(), src.group().id().toString());
            Snapshot checked = snapshot(workspaceId, src, request.startAt());
            if (!sameHash(checked.result().previewToken(), request.previewToken()))
                throw new ApiException(HttpStatus.CONFLICT,
                        "Sheet/Post/Task state changed since preview. Preview again; no tasks created");
            // SQL is the authoritative ownership check; all planning queries
            // already scope candidates to workspace_id. Do not rely on an
            // extra lazy JPA association check for authorization here.
            Boolean groupInWorkspace = db.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM facebook_groups
                         WHERE id = ? AND workspace_id = ?
                    )
                    """, Boolean.class, src.group().id(), workspaceId);
            if (!Boolean.TRUE.equals(groupInWorkspace))
                throw new ApiException(HttpStatus.CONFLICT,
                        "Facebook Group no longer belongs to this Workspace; no tasks created");
            int created = 0;
            for (int i = 0; i < checked.mapped().size(); i++) {
                Row row = checked.result().rows().get(i);
                if (!"READY_TO_CREATE".equals(row.action())) continue;
                UUID postId = row.postId();
                Boolean exists = db.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM group_publishing_tasks
                                      WHERE post_id=? AND facebook_group_id=?)
                        """, Boolean.class, postId, src.group().id());
                if (Boolean.TRUE.equals(exists))
                    throw new ApiException(HttpStatus.CONFLICT,
                            "Group Task was created concurrently. Entire plan rolled back");
                var post = posts.findById(postId).orElseThrow(() ->
                        new ApiException(HttpStatus.CONFLICT, "Post disappeared. Preview again"));
                // Re-check Post ownership against the actual posts table.
                // The former post.workspace.id check could disagree with the
                // content-scoped JDBC snapshot due to JPA association state.
                // Fail closed if DB ownership is wrong (never bypass tenant isolation).
                Boolean postInWorkspace = db.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM posts
                             WHERE id = ? AND workspace_id = ?
                        )
                        """, Boolean.class, postId, workspaceId);
                if (!Boolean.TRUE.equals(postInWorkspace))
                    throw new ApiException(HttpStatus.CONFLICT,
                            "Post is not in the selected Workspace (postId=" + postId +
                                    "); no tasks created");
                var group = groups.findById(src.group().id()).orElseThrow(() ->
                        new ApiException(HttpStatus.CONFLICT, "Group disappeared. Preview again"));
                GroupPublishingTask task = new GroupPublishingTask();
                task.post = post;
                task.facebookGroup = group;
                task.scheduledAt = row.scheduledAt();
                tasks.save(task); // existing entity semantics: default PENDING
                created++;
            }
            // Flush defaults and verify every newly created task is PENDING.
            tasks.flush();
            for (Row row : checked.result().rows()) {
                if (!"READY_TO_CREATE".equals(row.action())) continue;
                List<String> states = db.query("""
                        SELECT status FROM group_publishing_tasks
                         WHERE post_id=? AND facebook_group_id=?
                        """, (rs, n) -> rs.getString(1), row.postId(), src.group().id());
                if (states.size() != 1 || !"PENDING".equals(states.getFirst()))
                    throw new ApiException(HttpStatus.CONFLICT,
                            "Unexpected group-task status. Entire plan rolled back; no LIVE approval");
            }
            Result a = checked.result();
            return new Result(a.connectionId(), a.importId(), a.facebookGroupId(),
                    a.spreadsheetId(), a.tabName(), a.groupName(), false,
                    a.sourceRows(), a.readyToCreate(), a.existing(), a.blocked(), created,
                    a.intervalMinutes(), "", a.rows());
        });
    }

    @PostMapping("/links/preview")
    public WritebackResult linksPreview(@PathVariable UUID workspaceId, @RequestBody LinkRequest request) {
        access.admin(workspaceId);
        Source src = source(workspaceId, request == null ? null : request.sheetUrl());
        return writeback(workspaceId, src, true);
    }

    @PostMapping("/links/write")
    public WritebackResult linksWrite(@PathVariable UUID workspaceId, @RequestBody LinkRequest request) {
        access.admin(workspaceId);
        Source src = source(workspaceId, request == null ? null : request.sheetUrl());
        if (request == null || !Boolean.TRUE.equals(request.confirmWrite()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "confirmWrite=true required");
        if (!src.connection().writebackEnabled())
            throw new ApiException(HttpStatus.CONFLICT, "Google Sheet writeback is disabled");
        return writeback(workspaceId, src, false);
    }

    private Snapshot snapshot(UUID workspaceId, Source src, OffsetDateTime startAt) {
        Map<UUID, Boolean> already = existingTasks(src.group().id());
        List<Row> rows = new ArrayList<>();
        StringBuilder fingerprint = new StringBuilder(src.connection().id().toString())
                .append('|').append(src.group().id()).append('|').append(startAt.toInstant());
        int ready = 0, existing = 0, blocked = 0;
        // Sheet row index (not imported file row index) controls ordering and schedule.
        for (int i = 0; i < src.mapped().size(); i++) {
            Mapped m = src.mapped().get(i);
            PostCandidate p = m.match();
            OffsetDateTime at = startAt.plusMinutes((long) i * GAP_MINUTES);
            String action, reason;
            if (m.ambiguous()) {
                action = "BLOCKED";
                reason = "Multiple distinct imported Posts share this Sheet content";
            } else if (p == null) {
                action = "BLOCKED";
                reason = "Sheet content not found in enabled linked Excel imports";
            } else if (Boolean.TRUE.equals(already.get(p.postId()))) {
                action = "ALREADY_EXISTS";
                reason = "Group Task already exists for this Post and Group";
            } else if (importRowSkipped(p.processingStatus())) {
                action = "BLOCKED";
                reason = "Excel import row was skipped";
            } else if (!"APPROVED".equals(p.approvalStatus()) || !groupPostEligible(p.postStatus())) {
                action = "BLOCKED";
                reason = "Post is not APPROVED or eligible for Group scheduling";
            } else if (p.mediaCount() == 0) {
                action = "READY_TO_CREATE";
                reason = "TEXT: Matched approved Post, no media";
            } else if (p.videoCount() > 0) {
                // Video/Reel tasks may be scheduled, never automatically approved.
                // The Edge pilot supports ONE standalone MP4. Mixed video + photo,
                // multiple videos and invalid/missing URLs must remain blocked.
                if (p.videoCount() == 1 && p.mediaCount() == 1 && p.otherCount() == 0 &&
                        p.imageCount() == 0 && p.invalidMediaUrlCount() == 0) {
                    action = "READY_TO_CREATE";
                    reason = "VIDEO_OR_REEL_PENDING: one video URL; PENDING ONLY. " +
                            "MP4 codec/size and Facebook Group upload require one-task validation. " +
                            "Reel classification is not guaranteed by the Group Composer";
                } else {
                    action = "BLOCKED";
                    reason = "UNSUPPORTED_VIDEO_SET: exactly one standalone VIDEO/REEL " +
                            "with a valid URL required (no mixed photo/video, no multiple videos)";
                }
            } else if (p.otherCount() > 0 || p.imageCount() != p.mediaCount()) {
                action = "BLOCKED";
                reason = "UNSUPPORTED_MEDIA: media_type unknown or mixed";
            } else if (p.imageCount() > MAX_GROUP_IMAGES_PER_POST) {
                action = "BLOCKED";
                reason = "TOO_MANY_IMAGES: " + p.imageCount() +
                        " images; limit for scheduling is " + MAX_GROUP_IMAGES_PER_POST;
            } else if (p.invalidMediaUrlCount() > 0) {
                action = "BLOCKED";
                reason = "INVALID_IMAGE_URL: " + p.invalidMediaUrlCount() +
                        " image(s) have no valid HTTPS source/public URL";
            } else {
                // Image media is planned ONLY, never approved or dispatched here.
                // The Edge media pilot must verify each attachment before LIVE.
                action = "READY_TO_CREATE";
                reason = "IMAGE_PENDING_WORKER: " + p.imageCount() +
                        " image(s), PENDING schedule ONLY; requires one-task Edge media verification";
            }
            switch (action) {
                case "READY_TO_CREATE" -> ready++;
                case "ALREADY_EXISTS" -> existing++;
                default -> blocked++;
            }
            rows.add(new Row(m.sheet().row(), p == null ? null : p.postId(), action, reason, at));
            fingerprint.append('|').append(m.sheet().row()).append(':').append(m.sheet().content())
                    .append(':').append(p == null ? "missing" : p.postId())
                    // Hash the eligibility decision, not the raw statuses.
                    // READY -> PUBLISHED on another platform does not change
                    // whether this Post may have a new PENDING Group Task.
                    .append(':').append(p != null && groupPostEligible(p.postStatus()))
                    // Revoking approval MUST invalidate an earlier preview.
                    .append(':').append(p == null ? "" : p.approvalStatus())
                    // Import bookkeeping progress is irrelevant unless SKIPPED.
                    .append(':').append(p != null && importRowSkipped(p.processingStatus()))
                    .append(':').append(p == null ? "" : p.mediaCount())
                    .append(':').append(p == null ? "" : p.imageCount())
                    .append(':').append(p == null ? "" : p.videoCount())
                    .append(':').append(p == null ? "" : p.otherCount())
                    .append(':').append(p == null ? "" : p.invalidMediaUrlCount())
                    .append(':').append(p == null ? "" : p.mediaFingerprint())
                    .append(':').append(action).append(':').append(at);
        }
        Result result = new Result(src.connection().id(), null, src.group().id(),
                src.connection().spreadsheetId(), src.connection().tabName(), src.group().name(),
                true, src.mapped().size(), ready, existing, blocked, 0,
                GAP_MINUTES, sha256(fingerprint.toString()), List.copyOf(rows));
        return new Snapshot(result, src.mapped());
    }

    private Map<UUID, Boolean> existingTasks(UUID groupId) {
        Map<UUID, Boolean> result = new HashMap<>();
        db.query("SELECT DISTINCT post_id FROM group_publishing_tasks WHERE facebook_group_id=?",
                rs -> {
                    result.put(rs.getObject(1, UUID.class), true);
                }, groupId);
        return result;
    }

    private Source source(UUID workspaceId, String sheetUrl) {
        String sheetId = parseSheetId(sheetUrl);
        List<Connection> connections = db.query("""
                SELECT id, spreadsheet_id, tab_name, writeback_enabled
                  FROM google_sheet_connections
                 WHERE workspace_id=? AND spreadsheet_id=? AND sync_status='SYNCED'
                """, (rs, n) -> new Connection(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getString(3), rs.getBoolean(4)), workspaceId, sheetId);
        if (connections.size() != 1)
            throw new ApiException(HttpStatus.CONFLICT,
                    "Expected exactly one SYNCED Google Sheets connection for this link (found " +
                            connections.size() + "); no tasks created");
        Connection conn = connections.getFirst();
        List<Group> targets = db.query("""
                SELECT id, name, external_group_id FROM facebook_groups
                 WHERE workspace_id=? AND group_url LIKE 'https://%facebook.com/groups/%'
                 ORDER BY id
                """, (rs, n) -> new Group(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getString(3)), workspaceId);
        if (targets.size() != 1)
            throw new ApiException(HttpStatus.CONFLICT,
                    "Need exactly one configured Facebook Group (found " + targets.size() +
                            "); cannot auto-select the target");
        List<SheetRow> sheetRows = db.query("""
                SELECT source_row_number, content FROM google_sheet_staged_rows
                 WHERE connection_id=? AND present_in_sheet=TRUE
                   AND source_row_number BETWEEN 2 AND 10001
                   AND LENGTH(BTRIM(content)) > 0
                 ORDER BY source_row_number
                 LIMIT ?
                """, (rs, n) -> new SheetRow(rs.getInt(1), rs.getString(2)), conn.id(), MAX_ROWS + 1);
        if (sheetRows.isEmpty() || sheetRows.size() > MAX_ROWS)
            throw new ApiException(HttpStatus.CONFLICT,
                    "Sheet must contain 1.." + MAX_ROWS + " staged nonblank rows; no tasks created");
        Set<Integer> uniqueSheetRows = new HashSet<>();
        for (SheetRow r : sheetRows)
            if (!uniqueSheetRows.add(r.row()))
                throw new ApiException(HttpStatus.CONFLICT,
                        "Duplicate staged Google Sheet row " + r.row() + "; no tasks created");

        // Import-local source_row_number can repeat 2..101 in A01, A02, ... A10.
        // Join strictly by content, NOT by those local row numbers.
        List<PostCandidate> candidates = db.query("""
                SELECT e.id, r.source_row_number, p.id, p.content,
                       p.status, p.approval_status, r.processing_status,
                       media.media_count, media.image_count, media.video_count,
                       media.other_count, media.invalid_url_count, media.fingerprint
                  FROM google_sheet_import_links l
                  JOIN excel_imports e ON e.id=l.import_id AND e.workspace_id=l.workspace_id
                  JOIN excel_import_rows r ON r.import_id=e.id AND r.post_id IS NOT NULL
                  JOIN posts p ON p.id=r.post_id AND p.workspace_id=e.workspace_id
                  CROSS JOIN LATERAL (
                    SELECT COUNT(*)::int AS media_count,
                           COUNT(*) FILTER (
                              WHERE UPPER(BTRIM(COALESCE(pm.media_type,'')))='IMAGE')::int AS image_count,
                           COUNT(*) FILTER (
                              WHERE UPPER(BTRIM(COALESCE(pm.media_type,''))) IN ('VIDEO','REEL'))::int AS video_count,
                           COUNT(*) FILTER (
                              WHERE UPPER(BTRIM(COALESCE(pm.media_type,''))) NOT IN
                                   ('IMAGE','VIDEO','REEL'))::int AS other_count,
                           COUNT(*) FILTER (
                              WHERE COALESCE(NULLIF(BTRIM(pm.public_url),''),
                                             NULLIF(BTRIM(pm.source_url),'')) IS NULL
                                 OR COALESCE(NULLIF(BTRIM(pm.public_url),''),
                                             NULLIF(BTRIM(pm.source_url),''))
                                    !~* '^https://[^[:space:]]+$')::int AS invalid_url_count,
                           MD5(COALESCE(STRING_AGG(
                              CONCAT_WS(':', pm.id::text, pm.media_type,
                                  pm.public_url, pm.source_url, pm.display_order::text),
                              '|' ORDER BY pm.display_order, pm.id), '')) AS fingerprint
                      FROM post_media pm WHERE pm.post_id=p.id
                  ) media
                 WHERE l.connection_id=? AND l.workspace_id=? AND l.enabled=TRUE
                """, (rs, n) -> new PostCandidate(
                rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11),
                rs.getInt(12), rs.getString(13)), conn.id(), workspaceId);
        if (candidates.isEmpty())
            throw new ApiException(HttpStatus.CONFLICT, "No Posts in enabled linked Excel Imports");
        Map<String, Map<UUID, PostCandidate>> byContent = new HashMap<>();
        for (PostCandidate p : candidates)
            if (p.content() != null && !p.content().isBlank())
                byContent.computeIfAbsent(p.content(), k -> new LinkedHashMap<>()).putIfAbsent(p.postId(), p);
        List<Mapped> mapped = new ArrayList<>();
        for (SheetRow sheet : sheetRows) {
            Map<UUID, PostCandidate> matched = byContent.getOrDefault(sheet.content(), Map.of());
            mapped.add(new Mapped(sheet,
                    matched.size() == 1 ? matched.values().iterator().next() : null,
                    matched.size() > 1));
        }
        // A Post must map to exactly one current Sheet row. Otherwise, linking
        // one published permalink to several rows would silently corrupt Sheet L.
        Map<UUID, Integer> uses = new HashMap<>();
        for (Mapped match : mapped)
            if (match.match() != null)
                uses.merge(match.match().postId(), 1, Integer::sum);
        List<Mapped> unique = new ArrayList<>();
        for (Mapped match : mapped) {
            boolean repeated = match.match() != null && uses.get(match.match().postId()) > 1;
            unique.add(repeated ? new Mapped(match.sheet(), null, true) : match);
        }
        return new Source(conn, targets.getFirst(), List.copyOf(unique));
    }

    private void verifyLiveSheetContent(Source src) {
        String token = oauthToken();
        JsonNode live = readSheetValues(src.connection(), src.mapped().getLast().sheet().row(), token);
        for (Mapped m : src.mapped()) {
            if (!m.sheet().content().equals(cell(sheetRow(live, m.sheet().row()), 1))) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Live Sheet changed at row " + m.sheet().row() +
                                "; sync Google Sheets and preview again. No Group Tasks created");
            }
        }
    }

    private WritebackResult writeback(UUID workspaceId, Source source, boolean preview) {
        Connection c = source.connection();
        Group target = source.group();
        String token = oauthToken();
        // A single batch read also checks actual Sheet content, not only cached staging.
        int maxRow = source.mapped().getLast().sheet().row();
        JsonNode values = readSheetValues(c, maxRow, token);
        String expectedHeader = "Link Facebook Group " + target.externalId();
        String header = cell(sheetRow(values, 1), 11);
        if (!header.isBlank() && !header.equals(expectedHeader))
            throw new ApiException(HttpStatus.CONFLICT,
                    "Column L is already used by a different Group/header; no cells written");

        Map<UUID, Published> published = new HashMap<>();
        db.query("""
                SELECT post_id, COUNT(DISTINCT published_url) url_count, MIN(published_url) published_url
                  FROM group_publishing_tasks
                 WHERE facebook_group_id=? AND status='PUBLISHED' AND published_url IS NOT NULL
                 GROUP BY post_id
                """, (java.sql.ResultSet rs) -> {
            published.put(rs.getObject("post_id", UUID.class),
                    new Published(rs.getInt("url_count"), rs.getString("published_url")));
        }, target.id());

        List<GroupSheetWritebackController.Row> resultRows = new ArrayList<>();
        List<Map<String, Object>> updates = new ArrayList<>();
        int candidates = 0, synced = 0, blocked = 0, written = 0;
        for (Mapped match : source.mapped()) {
            SheetRow s = match.sheet();
            if (match.ambiguous() || match.match() == null) continue;
            Published link = published.get(match.match().postId());
            if (link == null) continue; // Only PUBLISHED tasks can be synchronized.
            candidates++;
            String url = link.url();
            String state, note;
            List<String> live = sheetRow(values, s.row());
            if (link.count() != 1 || !validGroupUrl(url, target.externalId())) {
                state = "BLOCKED";
                note = "Missing, invalid or conflicting Group permalink";
            } else if (!s.content().equals(cell(live, 1))) {
                state = "BLOCKED";
                note = "Live Google Sheet B differs from staged/imported content";
            } else if (url.equals(cell(live, 11))) {
                state = "ALREADY_SYNCED";
                note = "Existing Group permalink matches";
                synced++;
            } else if (!cell(live, 11).isBlank()) {
                state = "BLOCKED";
                note = "Existing column L data must not be overwritten";
            } else {
                state = preview ? "WOULD_WRITE" : "WRITTEN";
                note = "Only column L will change";
                if (!preview) updates.add(valueRange(c, "L" + s.row(), url));
            }
            if ("BLOCKED".equals(state)) blocked++;
            resultRows.add(new GroupSheetWritebackController.Row(s.row(), state, url, note));
        }
        if (!preview && !updates.isEmpty()) {
            if (header.isBlank()) updates.add(0, valueRange(c, "L1", expectedHeader));
            // No retries on HTTP failure; already-written rows are detected on next preview.
            for (int i = 0; i < updates.size(); i += SHEETS_WRITE_BATCH) {
                int end = Math.min(i + SHEETS_WRITE_BATCH, updates.size());
                sendUpdates(c, updates.subList(i, end), token);
            }
            written = (int) resultRows.stream().filter(row -> "WRITTEN".equals(row.status())).count();
        }
        return new WritebackResult(c.id(), null, target.id(), preview,
                candidates, written, synced, blocked, List.copyOf(resultRows));
    }

    private JsonNode readSheetValues(Connection c, int maxRow, String token) {
        String a1 = tab(c) + "A1:L" + maxRow;
        String url = base(c) + "/values/" + encode(a1) + "?valueRenderOption=FORMATTED_VALUE";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new IllegalStateException("Google Sheets read HTTP " + response.statusCode());
            return mapper.readTree(response.body()).path("values");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Google Sheets read failed: " + errorSummary(e));
        }
    }

    private void sendUpdates(Connection c, List<Map<String, Object>> ranges, String token) {
        try {
            String json = mapper.writeValueAsString(Map.of("valueInputOption", "RAW", "data", ranges));
            HttpRequest request = HttpRequest.newBuilder(URI.create(base(c) + "/values:batchUpdate"))
                    .timeout(Duration.ofSeconds(25)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new IllegalStateException("Google Sheets write HTTP " + response.statusCode());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Google Sheets write failed. Preview before next attempt: " + errorSummary(e));
        }
    }

    private static Map<String, Object> valueRange(Connection c, String cell, String value) {
        return Map.of("range", tab(c) + cell, "majorDimension", "ROWS",
                "values", List.of(List.of(value)));
    }

    private static List<String> sheetRow(JsonNode values, int row) {
        if (!values.isArray() || values.size() < row || !values.get(row - 1).isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.get(row - 1).forEach(v -> result.add(v.asText("")));
        return result;
    }

    private static String cell(List<String> row, int zeroIndex) {
        return zeroIndex < row.size() ? row.get(zeroIndex) : "";
    }

    private static String tab(Connection c) {
        return c.tabName() == null || c.tabName().isBlank() ? "" :
                "'" + c.tabName().replace("'", "''") + "'!";
    }

    private static String base(Connection c) {
        return "https://sheets.googleapis.com/v4/spreadsheets/" + c.spreadsheetId();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean validGroupUrl(String url, String groupId) {
        return url != null && url.matches("https://(?:www\\.)?facebook\\.com/groups/" +
                Pattern.quote(groupId) + "/(?:posts|permalink)/(?:[0-9]+|pfbid[A-Za-z0-9]+)/?");
    }

    private static String oauthToken() {
        try {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault().createScoped(List.of(SCOPE));
            credentials.refreshIfExpired();
            if (credentials.getAccessToken() == null) credentials.refresh();
            return credentials.getAccessToken().getTokenValue();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Google Sheets credentials unavailable");
        }
    }

    private static String parseSheetId(String link) {
        if (link == null || link.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "Google Sheets link is required");
        try {
            URI uri = URI.create(link.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                    !"docs.google.com".equalsIgnoreCase(uri.getHost()))
                throw new IllegalArgumentException("Expected HTTPS docs.google.com");
            String[] segments = uri.getPath().split("/");
            if (segments.length < 4 || !"spreadsheets".equals(segments[1]) ||
                    !"d".equals(segments[2]) || !ID.matcher(segments[3]).matches())
                throw new IllegalArgumentException("Invalid Google Sheets URL path");
            return segments[3];
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid Google Sheets URL");
        }
    }

    private static void requireStart(Request request) {
        if (request == null || request.startAt() == null ||
                !request.startAt().toInstant().isAfter(Instant.now().plusSeconds(300)))
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Start time must be at least five minutes in the future");
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean sameHash(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String errorSummary(Exception e) {
        String value = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return value.substring(0, Math.min(160, value.length()));
    }
}
