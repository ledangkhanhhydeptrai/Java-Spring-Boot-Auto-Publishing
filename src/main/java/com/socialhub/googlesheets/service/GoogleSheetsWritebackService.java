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
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter SHEET_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${app.google-sheets.writeback-worker-enabled:false}")
    private boolean workerEnabled;

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
                             String facebookId, String threadsId, String threadsPublishedUrl,
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
    private record EnabledLink(UUID connectionId, UUID workspaceId, UUID importId) {
    }

    @Scheduled(fixedDelayString = "${app.google-sheets.writeback-delay-ms:300000}",
            initialDelayString = "${app.google-sheets.writeback-initial-delay-ms:60000}")
    public void scheduled() {
        if (!workerEnabled) return;

        // BOTH the link and its connection must be opted in. Composite FKs from V30
        // guarantee that connection and import are in the same workspace.
        List<EnabledLink> links = db.query("""
                SELECT l.connection_id, l.workspace_id, l.import_id
                FROM google_sheet_import_links l
                JOIN google_sheet_connections c
                  ON c.id = l.connection_id AND c.workspace_id = l.workspace_id
                JOIN excel_imports e
                  ON e.id = l.import_id AND e.workspace_id = l.workspace_id
                WHERE l.enabled = TRUE
                  AND c.writeback_enabled = TRUE
                  AND c.sync_status = 'SYNCED'
                  AND e.social_account_id IS NOT NULL
                ORDER BY l.created_at, l.id
                """, (rs, n) -> new EnabledLink(
                rs.getObject("connection_id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("import_id", UUID.class)));

        for (EnabledLink link : links) {
            try {
                Connection c = connection(link.workspaceId(), link.connectionId());
                // Discover BOTH platforms for an imported Post. A Facebook-linked import
                // can also have a published Threads job on the SAME Post and workspace.
                // A published Threads job needs a real permalink, not merely a numeric ID.
                List<Integer> publishedRows = db.query("""
                                SELECT DISTINCT s.source_row_number
                                FROM google_sheet_staged_rows s
                                JOIN excel_import_rows r
                                  ON r.source_row_number = s.source_row_number
                                JOIN excel_imports e
                                  ON e.id = r.import_id
                                JOIN posts p
                                  ON p.id = r.post_id
                                 AND p.workspace_id = e.workspace_id
                                 AND p.content = s.content
                                JOIN publishing_jobs j
                                  ON j.post_id = p.id
                                JOIN social_accounts a
                                  ON a.id = j.social_account_id
                                 AND a.workspace_id = e.workspace_id
                                WHERE s.connection_id = ?
                                  AND s.present_in_sheet = TRUE
                                  AND s.source_row_number BETWEEN 2 AND 10001
                                  AND e.id = ? AND e.workspace_id = ?
                                  AND j.status = 'PUBLISHED'
                                  AND (
                                       (a.platform = 'FACEBOOK_PAGE'
                                        AND j.social_account_id = e.social_account_id
                                        AND j.external_post_id IS NOT NULL)
                                    OR (a.platform = 'THREADS'
                                        AND j.external_post_id IS NOT NULL
                                        AND (j.published_url LIKE 'https://www.threads.com/%'
                                          OR j.published_url LIKE 'https://www.threads.net/%'))
                                  )
                                ORDER BY s.source_row_number
                                """, (rs, n) -> rs.getInt(1),
                        link.connectionId(), link.importId(), link.workspaceId());

                int written = 0, unchanged = 0, conflicts = 0;
                for (int offset = 0; offset < publishedRows.size(); offset += BATCH_LIMIT) {
                    List<Integer> batch = publishedRows.subList(
                            offset, Math.min(offset + BATCH_LIMIT, publishedRows.size()));
                    // Process at most 50 published source rows per batch.
                    Report report = execute(c, link.importId(),
                            batch.getFirst(), batch.getLast(), BATCH_LIMIT,
                            "ALL", false);
                    written += report.written();
                    unchanged += report.unchanged();
                    conflicts += report.conflicts();
                    if (!report.errors().isEmpty()) {
                        log.warn("[SHEETS WRITEBACK] connection={} import={} errors={}",
                                link.connectionId(), link.importId(), report.errors());
                    }
                }
                log.info("[SHEETS WRITEBACK] connection={} import={} publishedRows={} written={} unchanged={} conflicts={}",
                        link.connectionId(), link.importId(), publishedRows.size(), written, unchanged, conflicts);
            } catch (Exception ex) {
                // Do not touch publishing jobs; the next scheduled poll will retry Sheet sync.
                log.warn("[SHEETS WRITEBACK] connection={} import={} error={}",
                        link.connectionId(), link.importId(), ex.getMessage(), ex);
            }
        }
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
        // Both platforms must belong to the SAME imported Post and workspace.
        // Do not cross-match only on text: different Posts may legitimately have
        // identical content, and Threads permalinks must not be assigned to those.
        // The original (immutable) Excel date is read from excel_import_rows.
        return db.query("""
                        SELECT s.source_row_number, s.content,
                               fb.external_post_id AS facebook_id,
                               th.external_post_id AS threads_id,
                               th.published_url AS threads_published_url,
                               r.source_publish_date,
                               COALESCE(fb.scheduled_at, th.scheduled_at) AS scheduled_at,
                               COALESCE(fb.published_at, th.published_at) AS published_at
                        FROM google_sheet_staged_rows s
                        JOIN excel_import_rows r
                          ON r.source_row_number = s.source_row_number
                         AND r.import_id = ?
                        JOIN excel_imports e
                          ON e.id = r.import_id AND e.workspace_id = ?
                        JOIN posts p
                          ON p.id = r.post_id AND p.workspace_id = e.workspace_id
                         AND p.content = s.content
                        LEFT JOIN LATERAL (
                            SELECT j.external_post_id, j.scheduled_at, j.published_at
                            FROM publishing_jobs j
                            JOIN social_accounts a ON a.id = j.social_account_id
                            WHERE j.post_id = p.id AND j.status = 'PUBLISHED'
                              AND a.platform = 'FACEBOOK_PAGE'
                              AND a.workspace_id = e.workspace_id
                              AND j.social_account_id = e.social_account_id
                              AND j.external_post_id IS NOT NULL
                            ORDER BY j.published_at DESC NULLS LAST, j.id DESC LIMIT 1
                        ) fb ON TRUE
                        LEFT JOIN LATERAL (
                            SELECT j.external_post_id, j.published_url,
                                   j.scheduled_at, j.published_at
                            FROM publishing_jobs j
                            JOIN social_accounts a ON a.id = j.social_account_id
                            WHERE j.post_id = p.id AND j.status = 'PUBLISHED'
                              AND a.platform = 'THREADS'
                              AND a.workspace_id = e.workspace_id
                              AND j.external_post_id IS NOT NULL
                              AND (j.published_url LIKE 'https://www.threads.com/%'
                                OR j.published_url LIKE 'https://www.threads.net/%')
                            ORDER BY j.published_at DESC NULLS LAST, j.id DESC LIMIT 1
                        ) th ON TRUE
                        WHERE s.connection_id = ? AND s.present_in_sheet = TRUE
                          AND s.source_row_number BETWEEN ? AND ?
                          AND (fb.external_post_id IS NOT NULL OR th.published_url IS NOT NULL)
                        ORDER BY s.source_row_number LIMIT 10000
                        """, (rs, n) -> new Candidate(
                        rs.getInt("source_row_number"), rs.getString("content"),
                        rs.getString("facebook_id"), rs.getString("threads_id"),
                        rs.getString("threads_published_url"),
                        rs.getObject("source_publish_date", LocalDate.class),
                        rs.getObject("scheduled_at", OffsetDateTime.class),
                        rs.getObject("published_at", OffsetDateTime.class)),
                importId, c.workspaceId(), c.id(), fromRow, toRow);
    }


    private synchronized Report execute(
            Connection c,
            UUID importId,
            int fromRow,
            int toRow,
            int limit,
            String platform,
            boolean dryRun
    ) {
        var rows = new ArrayList<Item>();
        var errors = new ArrayList<String>();

        int written = 0;
        int unchanged = 0;
        int conflicts = 0;

        String token = null;

        if (!dryRun) {
            token = oauthToken();
        }

        boolean doFb = "FACEBOOK_PAGE".equals(platform)
                || "ALL".equals(platform);

        boolean doThreads = "THREADS".equals(platform)
                || "ALL".equals(platform);

        for (Candidate item : candidates(c, importId, fromRow, toRow)) {

            if (rows.size() + conflicts >= limit) {
                break;
            }

            boolean fbPublished = item.facebookId() != null;

            boolean threadsPublished =
                    looksLikeThreadsLink(item.threadsPublishedUrl());

            if (!(doFb && fbPublished)
                    && !(doThreads && threadsPublished)) {
                continue;
            }

            String fbUrl = facebookUrl(item.facebookId());

            String threadsUrl = threadsPublished
                    ? item.threadsPublishedUrl()
                    : null;

            try {
                String scopedToken = token == null
                        ? oauthToken()
                        : token;

                List<String> existing =
                        readCells(c, item.row(), scopedToken);

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

                // =========================================
                // NO CHANGE
                // =========================================

                if (!changed) {
                    unchanged++;

                    if (!dryRun) {
                        if (doFb && fbUrl != null) {
                            audit(
                                    c,
                                    item.row(),
                                    "FACEBOOK_PAGE",
                                    item.facebookId(),
                                    fbUrl
                            );
                        }

                        if (doThreads && threadsPublished) {
                            audit(
                                    c,
                                    item.row(),
                                    "THREADS",
                                    item.threadsId(),
                                    threadsUrl
                            );
                        }
                    }

                    continue;
                }

                // Preview never writes to Sheets.
                if (dryRun) {
                    continue;
                }

                // =========================================
                // GOOGLE SHEETS WRITEBACK
                // =========================================

                if (doFb && fbUrl != null) {
                    validateHeaders(c, scopedToken);
                }

                writeCells(
                        c,
                        item.row(),
                        existing,
                        newF,
                        newG,
                        newH,
                        newI,
                        scopedToken
                );

                written++;

                if (doFb && fbUrl != null) {
                    audit(
                            c,
                            item.row(),
                            "FACEBOOK_PAGE",
                            item.facebookId(),
                            fbUrl
                    );
                }

                if (doThreads && threadsPublished) {
                    audit(
                            c,
                            item.row(),
                            "THREADS",
                            item.threadsId(),
                            threadsUrl
                    );
                }

            } catch (Exception e) {
                conflicts++;

                errors.add(
                        "row " + item.row()
                                + ": " + shortMessage(e)
                );
            }
        }

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

    private void validateHeaders(Connection c, String token) throws Exception {
        var header = readCells(c, 1, token);
        // Never overwrite an unrelated I-column header. J/K are left untouched.
        String existing = cell(header, 8);
        if (!existing.isBlank() && !"Link Facebook".equals(existing))
            throw new IllegalStateException("Column I has user header/data; refuse overwrite");
        if (existing.isBlank()) {
            sendUpdates(c, List.of(valueRange(c, "I1", "Link Facebook")), token);
        }
    }

    private List<String> readCells(Connection c, int row, String token) throws Exception {
        String a1 = tab(c) + "A" + row + ":K" + row;
        String path = base(c) + "/values/" + encode(a1) + "?valueRenderOption=FORMATTED_VALUE";
        HttpRequest req = HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).GET().build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("Google Sheets read HTTP " + res.statusCode());
        JsonNode values = mapper.readTree(res.body()).path("values");
        List<String> cells = new ArrayList<>();
        if (values.isArray() && !values.isEmpty() && values.get(0).isArray())
            values.get(0).forEach(v -> cells.add(v.asText("")));
        return cells;
    }

    private void writeCells(Connection c, int row, List<String> previous,
                            String f, String g, String h, String i, String token) throws Exception {
        List<Map<String, Object>> updates = new ArrayList<>();
        String[] values = {f, g, h, i};
        for (int n = 0; n < values.length; n++) {
            if (!values[n].equals(cell(previous, 5 + n))) {
                updates.add(valueRange(c, "" + (char) ('F' + n) + row, values[n]));
            }
        }
        // RAW keeps dd/MM/yyyy as a date-only display string and does not
        // re-interpret Facebook URLs or other cell text.
        sendUpdates(c, updates, token);
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

    private String oauthToken() {
        try {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(List.of(SHEETS_SCOPE));
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

