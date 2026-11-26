package com.socialhub.googlesheets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.socialhub.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Two-platform, opt-in Google Sheets writeback.
 * F = actual publish day only for overdue rescheduled published jobs (Facebook preferred).
 * G = common published status (at least one platform). H = Threads URL; I = Facebook URL.
 * Never creates or modifies J/K status columns; user data there is preserved.
 * Only DB-published jobs linked to the same imported Post and matching Sheet content are eligible.
 * No posts, jobs, queues or publishing APIs are created/called here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleSheetsWritebackService {
    private static final String SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets";
    private static final int BATCH_LIMIT = 50;
    private static final int PREVIEW_LIMIT = 10_000;
    private static final int READ_SPAN = 200;
    private static final int RANGES_PER_REQUEST = 20;
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter SHEET_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${app.google-sheets.writeback-worker-enabled:false}")
    private boolean workerEnabled;

    // Per-poll budget, not a limit on the total number of source rows.
    @Value("${app.google-sheets.writeback-max-rows-per-poll:10}")
    private int maxRowsPerPoll;
    private int nextLinkIndex;
    private final Map<EnabledLink, Integer> lastAttemptedRows = new HashMap<>();

    private record AuditKey(int row, String platform) {
    }

    private record AuditValue(String externalId, String url) {
    }

    public record SwitchStatus(UUID connectionId, boolean writebackEnabled) {
    }

    public record Item(int sheetRow, boolean facebookPublished, boolean threadsPublished,
                       String facebookUrl, String threadsUrl, String note) {
    }

    public record Report(UUID connectionId, boolean dryRun, int candidates, int written,
                         int unchanged, int conflicts, List<Item> rows, List<String> errors) {
    }

    private record Connection(UUID id, UUID workspaceId, String spreadsheetId, String tabName) {
    }

    private record Candidate(int row, String content,
                             String facebookId, String facebookPublishedUrl, String threadsId,
                             String threadsPublishedUrl,
                             LocalDate originalDate, OffsetDateTime scheduledAt,
                             OffsetDateTime publishedAt) {
    }

    public SwitchStatus setEnabled(UUID workspace, UUID id, boolean enabled) {
        connection(workspace, id);
        db.update("UPDATE google_sheet_connections SET writeback_enabled=?, updated_at=now() WHERE id=? AND workspace_id=?",
                enabled, id, workspace);
        return new SwitchStatus(id, enabled);
    }

    public Report preview(UUID workspace, UUID id, UUID importId, int fromRow, int toRow,
                          int limit, String platform) {
        validateRequest(workspace, importId, fromRow, toRow, limit, platform);
        return execute(connection(workspace, id), importId, fromRow, toRow, limit, platform, true);
    }

    public Report writeNow(UUID workspace, UUID id, UUID importId, int fromRow, int toRow,
                           int limit, String platform) {
        validateRequest(workspace, importId, fromRow, toRow, limit, platform);
        return execute(connection(workspace, id), importId, fromRow, toRow, limit, platform, false);
    }

    public Report previewAll(UUID workspace, UUID id, String platform) {
        if (platform == null || !Set.of("FACEBOOK_PAGE", "THREADS", "ALL").contains(platform))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid platform");
        Connection c = connection(workspace, id);
        Boolean linked = db.queryForObject("SELECT EXISTS(SELECT 1 FROM google_sheet_import_links WHERE connection_id=? AND workspace_id=? AND enabled=TRUE)", Boolean.class, c.id(), c.workspaceId());
        if (!Boolean.TRUE.equals(linked))
            throw new ApiException(HttpStatus.CONFLICT, "No enabled imports linked to this Sheet");
        return executeCandidates(c, candidates(c, null, 2, 10001), PREVIEW_LIMIT, platform, true);
    }

    private void validateRequest(UUID workspace, UUID importId, int fromRow, int toRow,
                                 int limit, String platform) {
        if (importId == null || fromRow < 2 || toRow < fromRow || toRow > 10001
                || limit < 1 || limit > BATCH_LIMIT
                || !("FACEBOOK_PAGE".equals(platform) || "THREADS".equals(platform) || "ALL".equals(platform))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "importId is required, fromRow=2..10001, toRow>=fromRow, limit=1..50, platform=FACEBOOK_PAGE|THREADS|ALL");
        }
        Boolean valid = db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM excel_imports
                  WHERE id = ? AND workspace_id = ? AND social_account_id IS NOT NULL)
                """, Boolean.class, importId, workspace);
        if (!Boolean.TRUE.equals(valid)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Excel import not found in workspace");
        }
    }

    // The worker uses the DB-backed V30 link table; no globally hardcoded Import ID.
    // Keep the same permission-scoped Preview/Sync endpoints for manual verification.
    private record EnabledLink(UUID connectionId, UUID workspaceId) {
    }

    @Scheduled(fixedDelayString = "${app.google-sheets.writeback-delay-ms:300000}",
            initialDelayString = "${app.google-sheets.writeback-initial-delay-ms:60000}")
    public void scheduled() {
        if (!workerEnabled) return;

        // BOTH the link and its connection must be opted in. Composite FKs from V30
        // guarantee that connection and import are in the same workspace.
        List<EnabledLink> links = db.query("""
                SELECT DISTINCT l.connection_id, l.workspace_id
                FROM google_sheet_import_links l
                JOIN google_sheet_connections c
                  ON c.id = l.connection_id AND c.workspace_id = l.workspace_id
                JOIN excel_imports e
                  ON e.id = l.import_id AND e.workspace_id = l.workspace_id
                WHERE l.enabled = TRUE
                  AND c.writeback_enabled = TRUE
                  AND c.sync_status = 'SYNCED'
                  AND e.social_account_id IS NOT NULL
                ORDER BY l.connection_id, l.workspace_id
                """, (rs, n) -> new EnabledLink(
                rs.getObject("connection_id", UUID.class),
                rs.getObject("workspace_id", UUID.class)));

        lastAttemptedRows.keySet().retainAll(links);
        if (links.isEmpty()) return;
        int budget = Math.max(1, Math.min(BATCH_LIMIT, maxRowsPerPoll));
        int start = Math.floorMod(nextLinkIndex, links.size());
        nextLinkIndex = (start + 1) % links.size();

        for (int n = 0; n < links.size() && budget > 0; n++) {
            EnabledLink link = links.get((start + n) % links.size());
            try {
                Connection c = connection(link.workspaceId(), link.connectionId());
                // Same verified mapping for scheduled and manual writeback.
                List<Candidate> pending = pendingCandidates(c);
                if (pending.isEmpty()) continue;
                // Rotate even when a row conflicts, so later rows are not starved.
                int lastRow = lastAttemptedRows.getOrDefault(link, 1);
                int offset = 0;
                while (offset < pending.size() && pending.get(offset).row() <= lastRow) offset++;
                int count = Math.min(budget, pending.size());
                List<Candidate> batch = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    batch.add(pending.get((offset + i) % pending.size()));
                }
                lastAttemptedRows.put(link, batch.getLast().row());
                budget -= count;
                Report report = executeCandidates(c, batch, count, "ALL", false);
                log.info("[SHEETS WRITEBACK] connection={} pendingRows={} attempted={} written={} unchanged={} conflicts={}",
                        link.connectionId(), pending.size(), count,
                        report.written(), report.unchanged(), report.conflicts());
                if (!report.errors().isEmpty()) {
                    log.warn("[SHEETS WRITEBACK] connection={} errors={}",
                            link.connectionId(), report.errors());
                }
            } catch (Exception ex) {
                log.warn("[SHEETS WRITEBACK] connection={} error={}",
                        link.connectionId(), ex.getMessage(), ex);
            }
        }
    }

    private List<Candidate> pendingCandidates(Connection c) {
        Map<AuditKey, AuditValue> completed = new HashMap<>();
        db.query("""
                SELECT source_row_number, platform, external_post_id, published_url
                FROM google_sheet_writeback_audit WHERE connection_id = ?
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            completed.put(new AuditKey(rs.getInt(1), rs.getString(2)),
                    new AuditValue(rs.getString(3), rs.getString(4)));
        }, c.id());

        return candidates(c, null, 2, 10001).stream().filter(item -> {
            String fbUrl = facebookUrl(item);
            String thUrl = item.threadsPublishedUrl();
            boolean fbPending = fbUrl != null && !Objects.equals(
                    completed.get(new AuditKey(item.row(), "FACEBOOK_PAGE")),
                    new AuditValue(item.facebookId(), fbUrl));
            boolean thPending = looksLikeThreadsLink(thUrl) && !Objects.equals(
                    completed.get(new AuditKey(item.row(), "THREADS")),
                    new AuditValue(item.threadsId(), thUrl));
            return fbPending || thPending;
        }).toList();
    }

    private Connection connection(UUID workspace, UUID id) {
        var list = db.query("""
                SELECT id, workspace_id, spreadsheet_id, tab_name FROM google_sheet_connections
                WHERE id=? AND workspace_id=? AND sync_status='SYNCED'
                """, (rs, n) -> new Connection(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4)), id, workspace);
        if (list.isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "Synced Sheet connection not found in workspace");
        return list.getFirst();
    }

    private List<Candidate> candidates(Connection c, UUID importId, int fromRow, int toRow) {
        // Resolve the sheet row separately from the local Excel row. Exact content
        // must be unique in this sheet and unambiguous within the import and other
        // enabled imports on this connection. Never infer an offset from file names.
        // Both platforms must still belong to the SAME imported Post/workspace.
        // The original Excel source row and original date are never modified.
        return db.query("""
                        WITH linked_rows AS (
                            SELECT e.id AS import_id, e.workspace_id, e.social_account_id,
                                   r.post_id, r.source_publish_date, r.processing_status, p.content,
                                   COUNT(*) OVER (PARTITION BY p.content) AS matches
                            FROM google_sheet_import_links l
                            JOIN excel_imports e ON e.id = l.import_id AND e.workspace_id = l.workspace_id
                            JOIN excel_import_rows r ON r.import_id = e.id
                            JOIN posts p ON p.id = r.post_id AND p.workspace_id = e.workspace_id
                            WHERE l.connection_id = ? AND l.workspace_id = ? AND l.enabled = TRUE
                        ), sheet_rows AS (
                            SELECT s.source_row_number, s.content,
                                   COUNT(*) OVER (PARTITION BY s.content) AS matches
                            FROM google_sheet_staged_rows s
                            WHERE s.connection_id = ? AND s.present_in_sheet = TRUE
                        )
                        SELECT s.source_row_number, s.content,
                               fb.external_post_id AS facebook_id,
                               fb.published_url AS facebook_published_url,
                               th.external_post_id AS threads_id,
                               th.published_url AS threads_published_url,
                               r.source_publish_date,
                               COALESCE(fb.scheduled_at, th.scheduled_at) AS scheduled_at,
                               COALESCE(fb.published_at, th.published_at) AS published_at
                        FROM sheet_rows s
                        JOIN linked_rows r ON r.content = s.content
                          AND r.matches = 1 AND s.matches = 1
                        LEFT JOIN LATERAL (
                            SELECT j.external_post_id, j.published_url, j.scheduled_at, j.published_at
                            FROM publishing_jobs j
                            JOIN social_accounts a ON a.id = j.social_account_id
                            WHERE j.post_id = r.post_id AND j.status = 'PUBLISHED'
                              AND a.platform = 'FACEBOOK_PAGE'
                              AND a.workspace_id = r.workspace_id
                              AND j.social_account_id = r.social_account_id
                              AND j.external_post_id IS NOT NULL
                            ORDER BY j.published_at DESC NULLS LAST, j.id DESC LIMIT 1
                        ) fb ON TRUE
                        LEFT JOIN LATERAL (
                            SELECT j.external_post_id, j.published_url,
                                   j.scheduled_at, j.published_at
                            FROM publishing_jobs j
                            JOIN social_accounts a ON a.id = j.social_account_id
                            WHERE j.post_id = r.post_id AND j.status = 'PUBLISHED'
                              AND a.platform = 'THREADS'
                              AND a.workspace_id = r.workspace_id
                              AND j.external_post_id IS NOT NULL
                              AND (j.published_url LIKE 'https://www.threads.com/%'
                                OR j.published_url LIKE 'https://www.threads.net/%')
                            ORDER BY j.published_at DESC NULLS LAST, j.id DESC LIMIT 1
                        ) th ON TRUE
                        WHERE (CAST(? AS uuid) IS NULL OR r.import_id = CAST(? AS uuid))
                          AND r.social_account_id IS NOT NULL
                          AND (r.processing_status IS NULL OR r.processing_status NOT LIKE 'SKIPPED_%')
                          AND s.source_row_number BETWEEN ? AND ?
                          AND (fb.external_post_id IS NOT NULL OR th.published_url IS NOT NULL)
                        ORDER BY s.source_row_number LIMIT 10000
                        """, (rs, n) -> new Candidate(
                        rs.getInt("source_row_number"), rs.getString("content"),
                        rs.getString("facebook_id"), rs.getString("facebook_published_url"), rs.getString("threads_id"),
                        rs.getString("threads_published_url"),
                        rs.getObject("source_publish_date", LocalDate.class),
                        rs.getObject("scheduled_at", OffsetDateTime.class),
                        rs.getObject("published_at", OffsetDateTime.class)),
                c.id(), c.workspaceId(), c.id(), importId, importId, fromRow, toRow);
    }


    private Report execute(
            Connection c, UUID importId, int fromRow, int toRow,
            int limit, String platform, boolean dryRun
    ) {
        Boolean linked = db.queryForObject("SELECT EXISTS(SELECT 1 FROM google_sheet_import_links WHERE connection_id=? AND workspace_id=? AND import_id=? AND enabled=TRUE)", Boolean.class, c.id(), c.workspaceId(), importId);
        if (!Boolean.TRUE.equals(linked))
            throw new ApiException(HttpStatus.CONFLICT, "Import not linked to this Sheet");
        return executeCandidates(c, candidates(c, importId, fromRow, toRow),
                limit, platform, dryRun);
    }

    private final Object writeLock = new Object();

    private Report executeCandidates(Connection c, List<Candidate> candidates, int limit,
                                     String platform, boolean dryRun) {
        if (dryRun) return executeBatch(c, candidates, limit, platform, true);
        synchronized (writeLock) {
            return executeBatch(c, candidates, limit, platform, false);
        }
    }

    private Report executeBatch(Connection c, List<Candidate> candidates, int limit,
                                String platform, boolean dryRun) {
        var rows = new ArrayList<Item>();
        var errors = new ArrayList<String>();

        int written = 0;
        int unchanged = 0;
        int conflicts = 0;

        boolean doFb = "FACEBOOK_PAGE".equals(platform) || "ALL".equals(platform);
        boolean doThreads = "THREADS".equals(platform) || "ALL".equals(platform);
        List<Candidate> selected = candidates.stream().filter(item ->
                        (doFb && item.facebookId() != null) ||
                                (doThreads && looksLikeThreadsLink(item.threadsPublishedUrl())))
                .limit(limit).toList();
        final String token = oauthToken();
        final Map<Integer, List<String>> live;
        try {
            live = readBatch(c, selected, token);
            validateHeaders(live.getOrDefault(1, List.of()));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.CONFLICT, "Writeback read/header validation failed: " + shortMessage(e));
        }
        if (selected.isEmpty()) return new Report(c.id(), dryRun, 0, 0, 0, 0, List.of(), List.of());
        List<Map<String, Object>> updates = new ArrayList<>();
        List<Candidate> accepted = new ArrayList<>();
        int changes = 0;
        for (Candidate item : selected) {


            boolean fbPublished = item.facebookId() != null;

            boolean threadsPublished =
                    looksLikeThreadsLink(item.threadsPublishedUrl());

            if (!(doFb && fbPublished)
                    && !(doThreads && threadsPublished)) {
                continue;
            }

            String fbUrl = facebookUrl(item);

            String threadsUrl = threadsPublished
                    ? item.threadsPublishedUrl()
                    : null;

            try {
                List<String> existing = live.getOrDefault(item.row(), List.of());
                if (doFb && fbPublished && fbUrl == null && !(doThreads && threadsPublished)) {
                    conflicts++;
                    errors.add("row " + item.row() + ": published Facebook job has no verified permalink");
                    continue;
                }

                // Verify source content before any write.
                if (!cell(existing, 1).equals(item.content())) {
                    conflicts++;

                    errors.add(
                            "row " + item.row()
                                    + ": Google Sheets content differs from matching Post"
                    );

                    continue;
                }

                String currentF = cell(existing, 5);
                String currentG = cell(existing, 6);
                String currentH = cell(existing, 7);
                String currentI = cell(existing, 8);

                // =========================================
                // COLUMN F: ACTUAL PUBLISH DATE
                // =========================================

                String newF = currentF;

                boolean dateEligible =
                        (doFb && fbUrl != null)
                                || (doThreads
                                && threadsPublished
                                && !fbPublished);

                // SH26 may not persist source_publish_date.
                // Fall back to the original date shown in Sheet F.
                LocalDate originalDay =
                        item.originalDate() != null
                                ? item.originalDate()
                                : parseSheetDate(currentF);

                if (dateEligible
                        && originalDay != null
                        && item.scheduledAt() != null
                        && item.publishedAt() != null) {

                    LocalDate scheduledDay =
                            item.scheduledAt()
                                    .atZoneSameInstant(VIETNAM_ZONE)
                                    .toLocalDate();

                    LocalDate actualDay =
                            item.publishedAt()
                                    .atZoneSameInstant(VIETNAM_ZONE)
                                    .toLocalDate();

                    // Only update an overdue/rescheduled date.
                    if (originalDay.isBefore(scheduledDay)
                            && !originalDay.equals(actualDay)) {

                        LocalDate sheetDay =
                                parseSheetDate(currentF);

                        // Protect unexpected Sheet modifications.
                        if (sheetDay == null
                                || (!sheetDay.equals(originalDay)
                                && !sheetDay.equals(actualDay))) {

                            conflicts++;

                            errors.add(
                                    "row " + item.row()
                                            + ": column F differs from original/rescheduled date; manual review"
                            );

                            continue;
                        }

                        newF = SHEET_DATE.format(actualDay);
                    }
                }

                // =========================================
                // COLUMN I: FACEBOOK URL
                // =========================================

                if (doFb
                        && fbUrl != null
                        && !currentI.isBlank()
                        && !currentI.equals(fbUrl)) {

                    conflicts++;

                    errors.add(
                            "row " + item.row()
                                    + ": existing Facebook URL differs; manual review"
                    );

                    continue;
                }

                // =========================================
                // COLUMN H: THREADS URL
                // =========================================

                if (doThreads
                        && threadsUrl != null
                        && !currentH.isBlank()
                        && !currentH.equals(threadsUrl)) {

                    conflicts++;

                    errors.add(
                            "row " + item.row()
                                    + ": existing Threads URL differs; manual review"
                    );

                    continue;
                }

                String newH =
                        doThreads
                                && currentH.isBlank()
                                && threadsUrl != null
                                ? threadsUrl
                                : currentH;

                String newI =
                        doFb && fbUrl != null
                                ? fbUrl
                                : currentI;

                // =========================================
                // COLUMN G: COMMON PUBLISHED STATUS
                // =========================================

                boolean hasFacebookEvidence =
                        (doFb && fbPublished && fbUrl != null)
                                || looksLikeFacebookLink(newI);

                boolean hasThreadsEvidence =
                        looksLikeThreadsLink(newH);

                String newG =
                        (hasFacebookEvidence || hasThreadsEvidence)
                                ? "Đã đăng"
                                : currentG;

                String note =
                        "Single status G (any platform); Threads H; Facebook I";

                if (!newF.equals(currentF)) {
                    note += "; overdue date F -> " + newF;
                }

                if (doFb && fbPublished && fbUrl == null) {
                    note +=
                            "; Facebook external ID cannot form a verified URL; no Facebook status write";
                }

                if (doThreads
                        && threadsPublished
                        && threadsUrl == null) {
                    note +=
                            "; Threads ID has no verified permalink; H preserved";
                }

                rows.add(
                        new Item(
                                item.row(),
                                fbPublished,
                                threadsPublished,
                                newI,
                                newH,
                                note
                        )
                );

                boolean changed =
                        !newF.equals(currentF)
                                || !newG.equals(currentG)
                                || !newH.equals(currentH)
                                || !newI.equals(currentI);

                if (!changed) {
                    unchanged++;
                } else {
                    changes++;
                    if (!dryRun) {
                        String[] values = {newF, newG, newH, newI};
                        for (int n = 0; n < values.length; n++) {
                            if (!values[n].equals(cell(existing, 5 + n)))
                                updates.add(valueRange(c, "" + (char) ('F' + n) + item.row(), values[n]));
                        }
                    }
                }
                accepted.add(item);
            } catch (Exception e) {
                conflicts++;

                errors.add(
                        "row " + item.row()
                                + ": " + shortMessage(e)
                );
            }
        }

        if (!dryRun) {
            try {
                // Recheck permission before sending changes. This is not a cross-system transaction.
                Boolean enabled = db.queryForObject("SELECT writeback_enabled FROM google_sheet_connections WHERE id=? AND workspace_id=? AND sync_status='SYNCED'", Boolean.class, c.id(), c.workspaceId());
                if (!Boolean.TRUE.equals(enabled)) throw new IllegalStateException("Writeback disabled");
                if (doFb && accepted.stream().anyMatch(item -> facebookUrl(item) != null)
                        && cell(live.getOrDefault(1, List.of()), 8).isBlank())
                    updates.add(valueRange(c, "I1", "Link Facebook"));
                sendUpdates(c, updates, token);
                written = changes;
            } catch (Exception e) {
                // No audit on failed/unknown Google response; a future poll reads live cells again.
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Writeback batch not confirmed: " + shortMessage(e));
            }
            for (Candidate item : accepted) {
                String fbUrl = facebookUrl(item);
                if (doFb && fbUrl != null) audit(c, item.row(), "FACEBOOK_PAGE", item.facebookId(), fbUrl);
                if (doThreads && looksLikeThreadsLink(item.threadsPublishedUrl()))
                    audit(c, item.row(), "THREADS", item.threadsId(), item.threadsPublishedUrl());
            }
        }
        log.info("[SHEETS WRITEBACK BATCH] connection={} preview={} selected={} written={} unchanged={} conflicts={}",
                c.id(), dryRun, selected.size(), written, unchanged, conflicts);
        return new Report(
                c.id(),
                dryRun,
                rows.size(),
                written,
                unchanged,
                conflicts,
                List.copyOf(rows),
                List.copyOf(errors)
        );
    }


    private void audit(Connection c, int row, String platform, String externalId, String url) {
        db.update("""
                INSERT INTO google_sheet_writeback_audit(connection_id,source_row_number,platform,external_post_id,published_url)
                VALUES (?,?,?,?,?) ON CONFLICT(connection_id,source_row_number,platform)
                DO UPDATE SET external_post_id=EXCLUDED.external_post_id,published_url=EXCLUDED.published_url,synced_at=now()
                """, c.id(), row, platform, externalId, url);
    }

    private static String normalizedHeader(String value) {
        return java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('đ', 'd').replaceAll("\\s+", " ");
    }

    private void validateHeaders(List<String> header) {
        String[] required = {"noi dung", "topic", "anh 1", "anh 2", "ngay dang", "trang thai", "link bai da dang"};
        for (int i = 0; i < required.length; i++) {
            if (!required[i].equals(normalizedHeader(cell(header, i + 1))))
                throw new IllegalStateException("Unexpected column " + (char) ('B' + i) + "; no write");
        }
        String facebook = normalizedHeader(cell(header, 8));
        if (!facebook.isBlank() && !"link facebook".equals(facebook))
            throw new IllegalStateException("Unexpected column I; no write");
    }

    private record Span(int first, int last) {
    }

    private static List<Span> planSpans(List<Integer> rowNumbers) {
        List<Span> spans = new ArrayList<>();
        spans.add(new Span(1, 1));
        List<Integer> numbers = rowNumbers.stream().distinct().sorted().toList();
        for (int pos = 0; pos < numbers.size(); ) {
            int first = numbers.get(pos++), last = first;
            while (pos < numbers.size() && numbers.get(pos) - first < READ_SPAN) last = numbers.get(pos++);
            spans.add(new Span(first, last));
        }
        return spans;
    }

    private Map<Integer, List<String>> readBatch(Connection c, List<Candidate> selected, String token) throws Exception {
        if (c.tabName() == null || c.tabName().isBlank())
            throw new IllegalStateException("Explicit Sheet tab required");
        List<Span> spans = planSpans(selected.stream().map(Candidate::row).toList());
        Map<Integer, List<String>> result = new HashMap<>();
        for (int offset = 0; offset < spans.size(); offset += RANGES_PER_REQUEST) {
            List<Span> batch = spans.subList(offset, Math.min(offset + RANGES_PER_REQUEST, spans.size()));
            StringBuilder path = new StringBuilder(base(c) + "/values:batchGet?valueRenderOption=FORMATTED_VALUE&majorDimension=ROWS");
            for (Span span : batch)
                path.append("&ranges=").append(encode(tab(c) + "A" + span.first() + ":I" + span.last()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(path.toString())).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new IllegalStateException("Google Sheets batch read HTTP " + response.statusCode());
            JsonNode ranges = mapper.readTree(response.body()).path("valueRanges");
            if (!ranges.isArray() || ranges.size() != batch.size())
                throw new IllegalStateException("Invalid batch read response");
            for (int i = 0; i < batch.size(); i++) {
                Span span = batch.get(i);
                JsonNode values = ranges.get(i).path("values");
                if (values.isMissingNode()) continue;
                if (!values.isArray() || values.size() > span.last() - span.first() + 1)
                    throw new IllegalStateException("Invalid row count in batch response");
                for (int j = 0; j < values.size(); j++) {
                    JsonNode row = values.get(j);
                    if (!row.isArray()) throw new IllegalStateException("Invalid row in batch response");
                    List<String> cells = new ArrayList<>();
                    row.forEach(value -> cells.add(value.asText("")));
                    result.put(span.first() + j, cells);
                }
            }
        }
        return result;
    }

    private static LocalDate parseSheetDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String value = raw.trim();

        List<DateTimeFormatter> formats = List.of(
                DateTimeFormatter.ofPattern("dd/MM/uuuu"),
                DateTimeFormatter.ofPattern("dd/MM/uuuu H:mm"),
                DateTimeFormatter.ISO_LOCAL_DATE
        );

        for (DateTimeFormatter format : formats) {
            try {
                return format.parse(value, LocalDate::from);
            } catch (DateTimeParseException ignored) {
                // Try the next date format.
            }
        }

        return null;
    }


    private Map<String, Object> valueRange(Connection c, String range, String text) {
        return Map.of("range", tab(c) + range, "majorDimension", "ROWS", "values", List.of(List.of(text)));
    }

    private void sendUpdates(Connection c, List<Map<String, Object>> data, String token) throws Exception {
        if (data.isEmpty()) return;
        String json = mapper.writeValueAsString(Map.of("valueInputOption", "RAW", "data", data));
        HttpRequest req = HttpRequest.newBuilder(URI.create(base(c) + "/values:batchUpdate"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        var response = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IllegalStateException("Google Sheets write HTTP " + response.statusCode());
    }

    private GoogleCredentials cachedCredentials;

    private synchronized String oauthToken() {
        try {
            if (cachedCredentials == null) cachedCredentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(List.of(SHEETS_SCOPE));
            GoogleCredentials credentials = cachedCredentials;
            credentials.refreshIfExpired();
            if (credentials.getAccessToken() == null) credentials.refresh();
            return credentials.getAccessToken().getTokenValue();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Sheets write credentials unavailable");
        }
    }

    private static String cell(List<String> cells, int idx) {
        return idx >= cells.size() || cells.get(idx) == null ? "" : cells.get(idx);
    }

    private static String tab(Connection c) {
        String name = c.tabName();
        if (name == null || name.isBlank()) return "";
        return "'" + name.replace("'", "''") + "'!";
    }

    private static String base(Connection c) {
        return "https://sheets.googleapis.com/v4/spreadsheets/" + c.spreadsheetId();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean looksLikeFacebookLink(String text) {
        return text != null && (text.startsWith("https://www.facebook.com/") || text.startsWith("https://facebook.com/"));
    }

    private static boolean looksLikeThreadsLink(String text) {
        return text != null && (text.startsWith("https://www.threads.com/")
                || text.startsWith("https://www.threads.net/"));
    }

    private static String facebookUrl(Candidate item) {
        String saved = item.facebookPublishedUrl();
        if (looksLikeFacebookLink(saved)) return saved;
        return facebookUrl(item.facebookId());
    }

    private static String facebookUrl(String id) {
        if (id == null || id.isBlank()) return null;
        if (id.matches("[0-9]+_[0-9]+")) return "https://www.facebook.com/" + id;
        if (id.matches("https://(?:www\\.)?facebook\\.com/[A-Za-z0-9_/?=&%-]+")) return id;
        return null;
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) return e.getClass().getSimpleName();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
