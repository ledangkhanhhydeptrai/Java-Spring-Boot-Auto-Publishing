package com.socialhub.googlesheets.service;

import com.socialhub.common.ApiException;
import com.socialhub.googlesheets.dto.GoogleSheetsDto;
import com.socialhub.googlesheets.dto.GoogleSheetsSyncDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Periodically mirrors Google Sheet data into staging tables, NOT into publishing tables.
 * Schedule/approval/publish must remain a separate explicitly authorized workflow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleSheetsSyncService {
    private static final Pattern SHEET_URL = Pattern.compile(
            "^https://docs\\.google\\.com/spreadsheets/d/([A-Za-z0-9_-]{20,})(?:[/\\?#].*)?$"
    );
    private static final Pattern GID = Pattern.compile("(?:[?#&])gid=([0-9]+)");
    private static final int PAGE_SIZE = 200;
    private static final int MAX_DATA_ROWS = 10_000;

    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final GoogleSheetsPreviewService sheets;

    @Value("${app.google-sheets.auto-sync-worker-enabled:false}")
    private boolean workerEnabled;

    private record Config(UUID id, UUID workspaceId, String url, String spreadsheetId,
                          String tabGid, String tabName) {
    }

    private record Entry(int number, String content, String topic, String image1, String image2,
                         String date, String status, String publishedUrl, String hash, boolean eligible) {
    }

    public GoogleSheetsSyncDto.Connection connect(UUID workspaceId, GoogleSheetsSyncDto.ConnectRequest req) {
        String url = req.spreadsheetUrl().trim();
        if (url.length() > 2048) throw bad("Google Sheet URL too long");
        Matcher m = SHEET_URL.matcher(url);
        if (!m.matches()) throw bad("Invalid Google Sheets URL");
        String id = m.group(1);
        Matcher gm = GID.matcher(url);
        String gid = gm.find() ? gm.group(1) : "0";
        String tab = req.tabName() == null || req.tabName().isBlank() ? null : req.tabName().trim();
        if (tab != null && (tab.length() > 80 || tab.contains("'") || tab.contains("\\n") || tab.contains("\\r"))) {
            throw bad("tabName must be a valid tab label without apostrophes; max 80 characters");
        }
        // Validate credentials, access and the tab before persisting the connection.
        sheets.preview(new GoogleSheetsDto.PreviewRequest(url, range(tab, 1, 2)));
        // Safe default: even if the request asks for autoSync, enable only after a manual sync.
        UUID connectionId = UUID.randomUUID();
        db.update("""
                INSERT INTO google_sheet_connections(id,workspace_id,spreadsheet_id,tab_gid,tab_name,spreadsheet_url,auto_sync)
                VALUES (?,?,?,?,?,?,false)
                ON CONFLICT (workspace_id,spreadsheet_id,tab_gid) DO NOTHING
                """, connectionId, workspaceId, id, gid, tab, url);
        UUID existing = db.queryForObject("""
                SELECT id FROM google_sheet_connections WHERE workspace_id=? AND spreadsheet_id=? AND tab_gid=?
                """, UUID.class, workspaceId, id, gid);
        return getConnection(workspaceId, Objects.requireNonNull(existing));
    }

    public List<GoogleSheetsSyncDto.Connection> list(UUID workspaceId) {
        return db.query("""
                SELECT * FROM google_sheet_connections WHERE workspace_id=? ORDER BY created_at DESC
                """, (rs, i) -> view(rs), workspaceId);
    }

    public GoogleSheetsSyncDto.Connection setAutoSync(UUID workspaceId, UUID connectionId, boolean enabled) {
        Config cfg = config(workspaceId, connectionId);
        if (enabled) {
            String state = db.queryForObject("SELECT sync_status FROM google_sheet_connections WHERE id=?", String.class, cfg.id());
            if (!"SYNCED".equals(state)) throw bad("Run a successful manual sync before enabling automatic sync");
        }
        db.update("UPDATE google_sheet_connections SET auto_sync=?,updated_at=now() WHERE id=? AND workspace_id=?",
                enabled, connectionId, workspaceId);
        return getConnection(workspaceId, connectionId);
    }

    public List<GoogleSheetsSyncDto.StagedRow> rows(UUID workspaceId, UUID connectionId, int limit, int offset) {
        config(workspaceId, connectionId);
        if (limit < 1 || limit > 200 || offset < 0) throw bad("limit must be 1..200 and offset >= 0");
        return db.query("""
                SELECT source_row_number,content,topic,image_1,image_2,source_publish_date,
                       source_status,source_published_url,publish_eligible,present_in_sheet
                FROM google_sheet_staged_rows WHERE connection_id=? ORDER BY source_row_number LIMIT ? OFFSET ?
                """, (rs, i) -> new GoogleSheetsSyncDto.StagedRow(
                rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getBoolean(9), rs.getBoolean(10)
        ), connectionId, limit, offset);
    }

    public GoogleSheetsSyncDto.SyncResult syncNow(UUID workspaceId, UUID connectionId) {
        return sync(config(workspaceId, connectionId));
    }

    @Scheduled(fixedDelayString = "${app.google-sheets.sync-delay-ms:300000}",
            initialDelayString = "${app.google-sheets.sync-initial-delay-ms:30000}")
    public void scheduledSync() {
        if (!workerEnabled) return;
        // One local process: serialize manual / scheduled invocations with a JVM lock.
        // For multi-instance deployments, keep only one worker and use a DB advisory lock.
        List<Config> configs = db.query("""
                SELECT id,workspace_id,spreadsheet_url,spreadsheet_id,tab_gid,tab_name
                FROM google_sheet_connections WHERE auto_sync=true ORDER BY created_at
                """, (rs, i) -> new Config(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6)
        ));
        for (Config cfg : configs) {
            try {
                sync(cfg);
            } catch (RuntimeException ex) {
                log.warn("[SHEETS SYNC] connection={} failed: {}", cfg.id(), ex.getMessage());
            }
        }
    }

    private synchronized GoogleSheetsSyncDto.SyncResult sync(Config cfg) {
        final List<Entry> staged = fetch(cfg);
        return Objects.requireNonNull(transaction.execute(tx -> {
            // All DB mutations are atomic; failed reads never invalidate existing staged data.
            int inserted = 0, updated = 0, unchanged = 0, excluded = 0;
            for (Entry r : staged) {
                if (!r.eligible()) excluded++;
                int changed = db.update("""
                                INSERT INTO google_sheet_staged_rows(
                                  connection_id,source_row_number,content,topic,image_1,image_2,
                                  source_publish_date,source_status,source_published_url,row_hash,publish_eligible,
                                  present_in_sheet,first_seen_at,last_seen_at,updated_at)
                                VALUES(?,?,?,?,?,?,?,?,?,?,?,true,now(),now(),now())
                                ON CONFLICT(connection_id,source_row_number) DO NOTHING
                                """, cfg.id(), r.number(), r.content(), r.topic(), r.image1(), r.image2(),
                        r.date(), r.status(), r.publishedUrl(), r.hash(), r.eligible());
                if (changed == 1) {
                    inserted++;
                    continue;
                }
                changed = db.update("""
                                UPDATE google_sheet_staged_rows
                                SET content=?,topic=?,image_1=?,image_2=?,source_publish_date=?,
                                    source_status=?,source_published_url=?,row_hash=?,publish_eligible=?,
                                    present_in_sheet=true,last_seen_at=now(),updated_at=now()
                                WHERE connection_id=? AND source_row_number=?
                                  AND (row_hash IS DISTINCT FROM ? OR present_in_sheet=false)
                                """, r.content(), r.topic(), r.image1(), r.image2(), r.date(), r.status(), r.publishedUrl(),
                        r.hash(), r.eligible(), cfg.id(), r.number(), r.hash());
                if (changed == 1) updated++;
                else unchanged++;
            }
            // A completely read sheet is authoritative about presence; no hard deletion.
            int missing;
            if (staged.isEmpty()) {
                missing = db.update("""
                        UPDATE google_sheet_staged_rows SET present_in_sheet=false,updated_at=now()
                        WHERE connection_id=? AND present_in_sheet=true
                        """, cfg.id());
            } else {
                int[] numbers = staged.stream().mapToInt(Entry::number).toArray();
                // Explicitly mark absence only for rows missing from the complete snapshot.
                missing = db.update("""
                        UPDATE google_sheet_staged_rows SET present_in_sheet=false,updated_at=now()
                        WHERE connection_id=? AND present_in_sheet=true
                          AND NOT (source_row_number = ANY (?))
                        """, ps -> {
                    ps.setObject(1, cfg.id());
                    java.sql.Array array = ps.getConnection().createArrayOf("integer", Arrays.stream(numbers).boxed().toArray());
                    ps.setArray(2, array);
                });
            }
            db.update("""
                    UPDATE google_sheet_connections SET sync_status='SYNCED',last_error=null,
                      last_synced_at=now(),updated_at=now() WHERE id=?
                    """, cfg.id());
            return new GoogleSheetsSyncDto.SyncResult(cfg.id(), staged.size(), inserted,
                    updated, unchanged, excluded, missing, "SYNCED");
        }));
    }

    private List<Entry> fetch(Config cfg) {
        List<Entry> data = new ArrayList<>();
        for (int start = 1; start <= MAX_DATA_ROWS + 1; start += PAGE_SIZE) {
            int end = Math.min(MAX_DATA_ROWS + 1, start + PAGE_SIZE - 1);
            var resp = sheets.preview(new GoogleSheetsDto.PreviewRequest(cfg.url(), range(cfg.tabName(), start, end)));
            for (var row : resp.rows()) {
                if (row.rowNumber() == 1) {
                    assertHeaders(row.cells());
                    continue;
                }
                if (row.rowNumber() > MAX_DATA_ROWS + 1) throw bad("Google Sheet exceeds 10000 data rows");
                List<String> c = row.cells();
                if (c.stream().allMatch(String::isBlank)) continue;
                String content = cell(c, 1), topic = cell(c, 2), im1 = cell(c, 3), im2 = cell(c, 4);
                String date = cell(c, 5), state = cell(c, 6), published = cell(c, 7);
                String statusNorm = normalize(state);
                boolean eligible = statusNorm.equals("chua dang") && published.isBlank() && !content.isBlank();
                String hash = sha(String.join("\u001F", content, topic, im1, im2, date, state, published));
                data.add(new Entry(row.rowNumber(), content, topic, im1, im2, date, state, published, hash, eligible));
            }
            // The values API may trim trailing blank rows but preserves leading positions within the range.
            if (resp.returnedRows() < end - start + 1) break;
        }
        return data;
    }

    private static void assertHeaders(List<String> cells) {
        String[] needed = {"noi dung", "topic", "anh 1", "anh 2", "ngay dang", "trang thai", "link bai da dang"};
        for (int i = 0; i < needed.length; i++) {
            if (!normalize(cell(cells, i + 1)).equals(needed[i])) {
                throw bad("Unexpected Google Sheets columns at " + (char) ('B' + i) + "; verify B1:H1");
            }
        }
    }

    private Config config(UUID workspaceId, UUID id) {
        var found = db.query("""
                SELECT id,workspace_id,spreadsheet_url,spreadsheet_id,tab_gid,tab_name
                FROM google_sheet_connections WHERE workspace_id=? AND id=?
                """, (rs, i) -> new Config(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), workspaceId, id);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Google Sheet connection not found");
        return found.getFirst();
    }

    private GoogleSheetsSyncDto.Connection getConnection(UUID workspaceId, UUID id) {
        var found = db.query("SELECT * FROM google_sheet_connections WHERE workspace_id=? AND id=?",
                (rs, i) -> view(rs), workspaceId, id);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Google Sheet connection not found");
        return found.getFirst();
    }

    private static GoogleSheetsSyncDto.Connection view(java.sql.ResultSet rs) throws java.sql.SQLException {
        var last = rs.getTimestamp("last_synced_at");
        return new GoogleSheetsSyncDto.Connection(rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getString("spreadsheet_id"),
                rs.getString("tab_gid"), rs.getString("tab_name"), rs.getBoolean("auto_sync"),
                rs.getString("sync_status"), last == null ? null : last.toInstant().toString(), rs.getString("last_error"));
    }

    private static String range(String tab, int start, int end) {
        return (tab == null ? "" : "'" + tab + "'!") + "A" + start + ":H" + end;
    }

    private static String cell(List<String> c, int idx) {
        return idx < c.size() ? c.get(idx).trim() : "";
    }

    private static String normalize(String value) {
        String n = Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replace('đ', 'd').replaceAll("\\s+", " ");
        return n;
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException bad(String s) {
        return new ApiException(HttpStatus.BAD_REQUEST, s);
    }
}
