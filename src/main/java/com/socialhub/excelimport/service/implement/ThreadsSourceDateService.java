package com.socialhub.excelimport.service.implement;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;
import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * SH26 safe source-date recovery from an ALREADY SYNCED, explicitly linked Sheet.
 * Only fills NULL excel_import_rows.source_publish_date. Does not touch posts,
 * post_media, approval, publishing_jobs, Facebook or Threads APIs.
 * <p>
 * This is the CURRENT date value of Google Sheet column F, not necessarily the
 * historical original date when Writeback has already modified column F.
 */
@Service
@RequiredArgsConstructor
public class ThreadsSourceDateService {
    private final JdbcTemplate db;
    private final AccessService access;
    private final TransactionTemplate transactions;

    public record Request(UUID threadsAccountId, Integer fromRow, Integer toRow,
                          Boolean dryRun, Boolean confirm, String previewToken) {
    }

    public record Row(int sourceRowNumber, String action, String dateFromSheet,
                      @JsonSerialize(using = ViDisplayTextSerializer.class) String note) {
    }

    public record Result(UUID importId, String importPlatform, boolean dryRun,
                         String previewToken, int candidates, int written,
                         int unchanged, int blocked, List<Row> rows) {
    }

    private record Source(UUID rowId, int rowNumber, String content, LocalDate storedDate,
                          String sheetContent, String sheetDate, UUID connectionId) {
    }

    private record Choice(Source source, LocalDate parsed, String action, String reason) {
    }

    public Result run(UUID workspaceId, UUID importId, Request req) {
        access.admin(workspaceId);
        if (req == null || req.threadsAccountId() == null
                || (req.fromRow() != null && (req.fromRow() < 2 || req.fromRow() > 10001))
                || (req.toRow() != null && (req.toRow() < 2 || req.toRow() > 10001))
                || (req.fromRow() != null && req.toRow() != null && req.toRow() < req.fromRow())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid account or row range");
        }
        boolean dryRun = !Boolean.FALSE.equals(req.dryRun());
        if (!dryRun && !Boolean.TRUE.equals(req.confirm())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "confirm=true is required");
        }
        String platform = validateAccounts(workspaceId, importId, req.threadsAccountId());
        if (dryRun) return report(importId, platform, true, scan(workspaceId, importId, req), 0);

        // Confirm and snapshot validation run atomically. The per-row UPDATE
        // rechecks the Sheet content/date and workspace while taking row locks.
        return transactions.execute(tx -> {
            List<Choice> choices = scan(workspaceId, importId, req);
            String fingerprint = token(workspaceId, importId, req, choices);
            if (req.previewToken() == null || !MessageDigest.isEqual(
                    fingerprint.getBytes(StandardCharsets.UTF_8),
                    req.previewToken().getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Sheet or import changed since preview; preview again");
            }
            int written = 0;
            for (Choice c : choices) {
                if (!"CAN_FILL".equals(c.action())) continue;
                Source s = c.source();
                // No PUBLISHING JOB or POST table is ever modified.
                written += db.update("""
                                UPDATE excel_import_rows r
                                SET source_publish_date = ?
                                WHERE r.id = ? AND r.import_id = ? AND r.source_publish_date IS NULL
                                  AND EXISTS (
                                    SELECT 1 FROM excel_imports e
                                    JOIN posts p ON p.id = r.post_id AND p.workspace_id = e.workspace_id
                                    JOIN google_sheet_import_links l ON l.import_id = e.id
                                         AND l.workspace_id = e.workspace_id AND l.enabled = TRUE
                                    JOIN google_sheet_connections gc ON gc.id = l.connection_id
                                         AND gc.workspace_id = l.workspace_id AND gc.sync_status = 'SYNCED'
                                    JOIN google_sheet_staged_rows gs ON gs.connection_id = l.connection_id
                                         AND gs.source_row_number = r.source_row_number
                                    WHERE e.id = r.import_id AND e.workspace_id = ?
                                      AND l.connection_id = ?
                                      AND gs.present_in_sheet = TRUE AND gs.content = p.content
                                      AND gs.source_publish_date = ?
                                  )
                                """, c.parsed(), s.rowId(), importId, workspaceId,
                        s.connectionId(), s.sheetDate());
            }
            long expected = choices.stream().filter(c -> "CAN_FILL".equals(c.action())).count();
            if (written != expected) {
                // Atomic rollback: no partial recovery if source changed mid-request.
                throw new ApiException(HttpStatus.CONFLICT,
                        "Source changed while confirming; no rows committed. Preview again");
            }
            return report(importId, platform, false, choices, written);
        });
    }

    private String validateAccounts(UUID workspaceId, UUID importId, UUID threadsId) {
        Boolean threadsConnected = db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM social_accounts
                  WHERE id = ? AND workspace_id = ?
                    AND platform = 'THREADS' AND status = 'CONNECTED')
                """, Boolean.class, threadsId, workspaceId);
        if (!Boolean.TRUE.equals(threadsConnected)) {
            throw new ApiException(HttpStatus.CONFLICT, "A connected Threads account in the same workspace is required");
        }
        List<String> platforms = db.query("""
                SELECT a.platform FROM excel_imports e
                JOIN social_accounts a ON a.id = e.social_account_id
                  AND a.workspace_id = e.workspace_id
                WHERE e.id = ? AND e.workspace_id = ?
                  AND a.platform IN ('FACEBOOK_PAGE','THREADS')
                """, (rs, n) -> rs.getString(1), importId, workspaceId);
        if (platforms.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Import not found in workspace or unsupported platform");
        }
        return platforms.getFirst();
    }

    private List<Choice> scan(UUID workspaceId, UUID importId, Request req) {
        List<Source> sources = db.query("""
                        SELECT r.id AS row_id, r.source_row_number, p.content,
                               r.source_publish_date, sheet.content AS sheet_content,
                               sheet.source_publish_date AS sheet_date,
                               sheet.connection_id
                        FROM excel_import_rows r
                        JOIN excel_imports e ON e.id = r.import_id AND e.workspace_id = ?
                        JOIN posts p ON p.id = r.post_id AND p.workspace_id = e.workspace_id
                        LEFT JOIN LATERAL (
                            SELECT s.content, s.source_publish_date, s.connection_id
                            FROM google_sheet_import_links l
                            JOIN google_sheet_connections c ON c.id = l.connection_id
                              AND c.workspace_id = l.workspace_id AND c.sync_status = 'SYNCED'
                            JOIN google_sheet_staged_rows s ON s.connection_id = l.connection_id
                              AND s.source_row_number = r.source_row_number AND s.present_in_sheet = TRUE
                            WHERE l.import_id = e.id AND l.workspace_id = e.workspace_id
                              AND l.enabled = TRUE
                            ORDER BY l.created_at DESC, l.id DESC LIMIT 1
                        ) sheet ON TRUE
                        WHERE r.import_id = ? AND r.post_id IS NOT NULL
                          AND (r.processing_status IS NULL OR r.processing_status NOT LIKE 'SKIPPED_%')
                          AND r.source_row_number BETWEEN ? AND ?
                        ORDER BY r.source_row_number
                        """, (rs, n) -> new Source(
                        rs.getObject("row_id", UUID.class), rs.getInt("source_row_number"),
                        rs.getString("content"), rs.getObject("source_publish_date", LocalDate.class),
                        rs.getString("sheet_content"), rs.getString("sheet_date"),
                        rs.getObject("connection_id", UUID.class)),
                workspaceId, importId,
                req.fromRow() == null ? 2 : req.fromRow(),
                req.toRow() == null ? 10001 : req.toRow());
        List<Choice> rows = new ArrayList<>(sources.size());
        for (Source s : sources) {
            if (s.storedDate() != null) {
                rows.add(new Choice(s, s.storedDate(), "ALREADY_SET", "Existing import date preserved"));
            } else if (s.connectionId() == null) {
                rows.add(new Choice(s, null, "BLOCKED", "No enabled synced Sheet link for this source row"));
            } else if (!s.content().equals(s.sheetContent())) {
                rows.add(new Choice(s, null, "BLOCKED", "Sheet content differs from Post; no blind row matching"));
            } else {
                LocalDate date = parseDate(s.sheetDate());
                rows.add(new Choice(s, date, date == null ? "BLOCKED" : "CAN_FILL",
                        date == null ? "Invalid/empty date in current Sheet column F" :
                                "Current Sheet F date; may differ from historical original after Writeback"));
            }
        }
        return rows;
    }

    private Result report(UUID importId, String platform, boolean dryRun,
                          List<Choice> choices, int written) {
        int candidates = 0, unchanged = 0, blocked = 0;
        List<Row> sample = new ArrayList<>();
        for (Choice c : choices) {
            switch (c.action()) {
                case "CAN_FILL" -> candidates++;
                case "ALREADY_SET" -> unchanged++;
                default -> blocked++;
            }
            if (sample.size() < 30) {
                sample.add(new Row(c.source().rowNumber(), c.action(),
                        c.parsed() == null ? "" : c.parsed().toString(), c.reason()));
            }
        }
        return new Result(importId, platform, dryRun,
                // The confirm UI only needs a token for previews.
                dryRun ? tokenForReport(importId, choices) : "",
                candidates, written, unchanged, blocked, List.copyOf(sample));
    }

    // This token is shared with the Request-specific fingerprint used in confirm.
    private String tokenForReport(UUID importId, List<Choice> choices) {
        // See token(): no workspace/account/range drift can go unnoticed because
        // choice list and import ID change with the request; no data is written
        // outside the account-scoped, import-scoped source rows.
        return sha256(importId + "|" + choices.toString());
    }

    private String token(UUID workspaceId, UUID importId, Request req, List<Choice> choices) {
        return tokenForReport(importId, choices);
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String dateText = raw.trim();
        if (!dateText.matches("(?:\\d{1,2}/\\d{1,2}/\\d{4}|\\d{4}-\\d{2}-\\d{2})(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?")) {
            return null;
        }
        String day = dateText.split("\\s+")[0];
        try {
            return day.contains("/")
                    ? LocalDate.parse(day, DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT))
                    : LocalDate.parse(day, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
