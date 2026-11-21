package com.socialhub.excelimport.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.publishingjob.service.interfaces.PublishingJobService;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;

/**
 * Creates Threads jobs on the ORIGINAL Post linked to an Excel/Sheets row.
 * No second Excel import, no Post cloning, no direct Meta calls, no silent LIVE dispatch.
 * Preview is read-only. Confirm requires a matching snapshot token.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelThreadsBridgeService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate db;
    private final AccessService access;
    private final PublishingJobService publishingJobs;
    private final TransactionTemplate transactions;

    public record Request(UUID threadsAccountId, LocalDate startDate, LocalTime startTime,
                          Integer intervalMinutes, Integer maxRows, Boolean dryRun,
                          Boolean confirmLive, String previewToken, Integer fromRow, Integer toRow) { }

    public record PlannedRow(int sourceRowNumber, UUID postId, String action,
                             OffsetDateTime scheduledAt, String note) { }

    public record Result(UUID importId, UUID threadsAccountId, boolean dryRun,
                         String previewToken, int eligible, int created, int alreadyPlanned,
                         int blocked, List<PlannedRow> rows, List<String> errors) { }

    private record Source(int row, UUID postId, String content, String postStatus,
                          String approval, LocalDate originalDate, LocalTime publishTime,
                          boolean jobExists, boolean mediaReady) { }

    private record Snapshot(String token, List<PlannedRow> rows) { }

    public Result plan(UUID workspaceId, UUID importId, Request req) {
        access.admin(workspaceId);
        validateRequest(req);
        // Confirm is explicitly opt-in. Never do writes during preview.
        boolean dryRun = !Boolean.FALSE.equals(req.dryRun());
        if (!dryRun && !Boolean.TRUE.equals(req.confirmLive())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "confirmLive=true is required");
        }
        validateLinkage(workspaceId, importId, req.threadsAccountId());
        Snapshot snapshot = snapshot(importId, req);
        int eligible = (int) snapshot.rows().stream().filter(r -> "READY_TO_CREATE".equals(r.action())).count();
        int already = (int) snapshot.rows().stream().filter(r -> "ALREADY_PLANNED".equals(r.action())).count();
        int blocked = (int) snapshot.rows().stream().filter(r -> "BLOCKED".equals(r.action())).count();
        if (dryRun) {
            return new Result(importId, req.threadsAccountId(), true, snapshot.token(),
                    eligible, 0, already, blocked, snapshot.rows(), List.of());
        }
        if (req.previewToken() == null || !MessageDigest.isEqual(
                req.previewToken().getBytes(StandardCharsets.UTF_8),
                snapshot.token().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.CONFLICT, "Data changed since preview; preview again");
        }
        int created = 0;
        List<PlannedRow> actual = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (PlannedRow row : snapshot.rows()) {
            if (!"READY_TO_CREATE".equals(row.action())) {
                actual.add(row);
                continue;
            }
            try {
                // Job creation uses the existing service's approval, permission and account checks.
                // A single transaction prevents a partial job INSERT on failure.
                transactions.executeWithoutResult(tx -> {
                    // Serialize confirmations for the same Post+Threads account.
                    db.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))",
                            (rs, i) -> rs.getObject(1), row.postId().toString(), req.threadsAccountId().toString());
                    Boolean exists = db.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM publishing_jobs
                              WHERE post_id=? AND social_account_id=?)
                            """, Boolean.class, row.postId(), req.threadsAccountId());
                    if (Boolean.TRUE.equals(exists)) {
                        throw new AlreadyExists();
                    }
                    if (!row.scheduledAt().toInstant().isAfter(Instant.now().plusSeconds(60))) {
                        throw new ApiException(HttpStatus.CONFLICT, "Scheduled time is too close; preview again");
                    }
                    publishingJobs.create(row.postId(), req.threadsAccountId(), row.scheduledAt());
                });
                created++;
                actual.add(new PlannedRow(row.sourceRowNumber(), row.postId(), "JOB_CREATED", row.scheduledAt(),
                        "Threads job created; publisher will run when due"));
            } catch (AlreadyExists ex) {
                already++;
                actual.add(new PlannedRow(row.sourceRowNumber(), row.postId(), "ALREADY_PLANNED", null,
                        "Threads Job already exists; not duplicated"));
            } catch (RuntimeException ex) {
                blocked++;
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                errors.add("sheet row " + row.sourceRowNumber() + ": " + message);
                actual.add(new PlannedRow(row.sourceRowNumber(), row.postId(), "BLOCKED", row.scheduledAt(), message));
                log.warn("[THREADS BRIDGE] failed import={} row={} post={} reason={}",
                        importId, row.sourceRowNumber(), row.postId(), message);
            }
        }
        return new Result(importId, req.threadsAccountId(), false, snapshot.token(),
                eligible, created, already, blocked, List.copyOf(actual), List.copyOf(errors));
    }

    private void validateRequest(Request req) {
        if (req == null || req.threadsAccountId() == null || req.startDate() == null
                || req.startTime() == null || req.intervalMinutes() == null || req.maxRows() == null
                || req.intervalMinutes() < 1 || req.intervalMinutes() > 1440
                || req.maxRows() < 1 || req.maxRows() > 50
                || (req.fromRow() != null && req.fromRow() < 2)
                || (req.toRow() != null && req.toRow() > 10001)
                || (req.fromRow() != null && req.toRow() != null && req.toRow() < req.fromRow())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "threadsAccountId, startDate, startTime, intervalMinutes(1..1440), maxRows(1..50) are required");
        }
        if (!ZonedDateTime.of(req.startDate(), req.startTime(), ZONE).toInstant()
                .isAfter(Instant.now().plusSeconds(300))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Start time must be at least 5 minutes in the future");
        }
    }

    private void validateLinkage(UUID workspaceId, UUID importId, UUID threadsAccountId) {
        Boolean goodImport = db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM excel_imports e
                WHERE e.id=? AND e.workspace_id=? AND e.social_account_id IS NOT NULL)
                """, Boolean.class, importId, workspaceId);
        if (!Boolean.TRUE.equals(goodImport)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Excel import not found in workspace");
        }
        Boolean goodAccount = db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM social_accounts a
                WHERE a.id=? AND a.workspace_id=? AND a.platform='THREADS' AND a.status='CONNECTED')
                """, Boolean.class, threadsAccountId, workspaceId);
        if (!Boolean.TRUE.equals(goodAccount)) {
            throw new ApiException(HttpStatus.CONFLICT, "Connected Threads account required in same workspace");
        }
    }

    private Snapshot snapshot(UUID importId, Request req) {
        List<Source> sources = db.query("""
                SELECT r.source_row_number, r.post_id, p.content, p.status AS post_status,
                       p.approval_status, r.source_publish_date, e.publish_time,
                       EXISTS(SELECT 1 FROM publishing_jobs j
                           WHERE j.post_id=p.id AND j.social_account_id=?) AS has_job,
                       NOT EXISTS(SELECT 1 FROM post_media m WHERE m.post_id=p.id
                           AND (m.public_url IS NULL OR btrim(m.public_url)='' OR m.media_type<>'IMAGE')) AS media_ready
                FROM excel_import_rows r
                JOIN excel_imports e ON e.id=r.import_id
                JOIN posts p ON p.id=r.post_id AND p.workspace_id=e.workspace_id
                WHERE r.import_id=? AND r.post_id IS NOT NULL
                  AND (r.processing_status IS NULL OR r.processing_status NOT LIKE 'SKIPPED_%')
                  AND r.source_row_number BETWEEN ? AND ?
                ORDER BY r.source_row_number
                """, (rs, i) -> new Source(rs.getInt("source_row_number"),
                rs.getObject("post_id", UUID.class), rs.getString("content"),
                rs.getString("post_status"), rs.getString("approval_status"),
                rs.getObject("source_publish_date", LocalDate.class),
                rs.getObject("publish_time", LocalTime.class), rs.getBoolean("has_job"),
                rs.getBoolean("media_ready")), req.threadsAccountId(), importId,
                req.fromRow() == null ? 2 : req.fromRow(),
                req.toRow() == null ? 10001 : req.toRow());

        List<Instant> occupied = db.query("""
                SELECT scheduled_at FROM publishing_jobs
                WHERE social_account_id=? AND status IN ('PENDING','PROCESSING','RETRY')
                  AND scheduled_at IS NOT NULL
                """, (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant(),
                req.threadsAccountId());

        int selected = 0;
        long nextIndex = 0;
        Instant earliest = Instant.now().plusSeconds(300);
        List<PlannedRow> planned = new ArrayList<>();
        for (Source s : sources) {
            if (s.jobExists()) {
                planned.add(new PlannedRow(s.row(), s.postId(), "ALREADY_PLANNED", null,
                        "Threads Job already exists"));
                continue;
            }
            if (selected >= req.maxRows()) break;
            if (s.originalDate() == null || s.publishTime() == null || s.content() == null
                    || s.content().isBlank() || s.content().length() > 500
                    || !"APPROVED".equals(s.approval())
                    || !Set.of("READY", "SCHEDULED").contains(s.postStatus()) || !s.mediaReady()) {
                planned.add(new PlannedRow(s.row(), s.postId(), "BLOCKED", null,
                        "Post must be READY/SCHEDULED, APPROVED, <=500 characters, dated and media ready"));
                continue;
            }
            ZonedDateTime original = ZonedDateTime.of(s.originalDate(), s.publishTime(), ZONE);
            Instant due;
            if (original.toInstant().isAfter(Instant.now())) {
                due = original.toInstant(); // Future original date must never move.
                if (!due.isAfter(earliest)) {
                    planned.add(new PlannedRow(s.row(), s.postId(), "BLOCKED", null,
                            "Original future slot is too close; do not reschedule it"));
                    continue;
                }
                if (collides(due, occupied, req.intervalMinutes())) {
                    planned.add(new PlannedRow(s.row(), s.postId(), "BLOCKED", null,
                            "Original future slot conflicts; do not reschedule it"));
                    continue;
                }
            } else {
                // Expired dates get the earliest free replacement slot.
                due = null;
                while (nextIndex < 50_000) {
                    Instant candidate = ZonedDateTime.of(req.startDate(), req.startTime(), ZONE)
                            .plusMinutes(nextIndex++ * req.intervalMinutes()).toInstant();
                    if (candidate.isAfter(earliest) && !collides(candidate, occupied, req.intervalMinutes())) {
                        due = candidate;
                        break;
                    }
                }
                if (due == null) {
                    planned.add(new PlannedRow(s.row(), s.postId(), "BLOCKED", null,
                            "No free replacement schedule"));
                    continue;
                }
            }
            occupied.add(due);
            selected++;
            String note = original.toInstant().isAfter(Instant.now())
                    ? "KEEP_ORIGINAL: future Excel date preserved"
                    : "RESCHEDULED: original Excel date preserved for audit";
            planned.add(new PlannedRow(s.row(), s.postId(), "READY_TO_CREATE",
                    due.atZone(ZONE).toOffsetDateTime(), note));
        }
        StringBuilder signature = new StringBuilder(importId + "|" + req.threadsAccountId() + "|"
                + req.startDate() + "|" + req.startTime() + "|" + req.intervalMinutes() + "|" + req.maxRows()
                + "|" + req.fromRow() + "|" + req.toRow());
        sources.forEach(s -> signature.append("|source:").append(s));
        planned.forEach(r -> signature.append("|plan:").append(r));
        return new Snapshot(sha256(signature.toString()), List.copyOf(planned));
    }

    private boolean collides(Instant time, List<Instant> existing, int minutes) {
        for (Instant occupied : existing) {
            if (Duration.between(time, occupied).abs().compareTo(Duration.ofMinutes(minutes)) < 0) return true;
        }
        return false;
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class AlreadyExists extends RuntimeException { }
}
