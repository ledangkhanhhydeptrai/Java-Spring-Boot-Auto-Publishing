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
                          Boolean confirmLive, String previewToken, Integer fromRow, Integer toRow) {
    }

    public record PlannedRow(int sourceRowNumber, UUID postId, String action,
                             OffsetDateTime scheduledAt, String note) {
    }

    public record Result(UUID importId, UUID threadsAccountId, boolean dryRun,
                         String previewToken, int eligible, int created, int alreadyPlanned,
                         int blocked, List<PlannedRow> rows, List<String> errors) {
    }

    private record Source(int row, UUID postId, String content, String postStatus,
                          String approval, LocalDate originalDate, LocalTime publishTime,
                          boolean jobExists, boolean mediaReady, String dateOrigin) {
    }

    private record Snapshot(String token, List<PlannedRow> rows) {
    }

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
                    lockAccount(req.threadsAccountId());
                    validateLinkage(workspaceId, importId, req.threadsAccountId());
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
                    if (collides(row.scheduledAt().toInstant(), occupiedSlots(req.threadsAccountId()), req.intervalMinutes())) {
                        throw new ApiException(HttpStatus.CONFLICT, "Threads slot changed; preview again");
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

    private List<Source> readSources(UUID importId, Request req) {
        return db.query("""
                        SELECT r.source_row_number, r.post_id, p.content, p.status AS post_status,
                               p.approval_status, r.source_publish_date, e.publish_time,
                               EXISTS(SELECT 1 FROM publishing_jobs j
                                   WHERE j.post_id=p.id AND j.social_account_id=?) AS has_job,
                               -- Match the actual Threads publisher preflight:
                               -- TEXT / up to 20 IMAGE / one VIDEO or REEL (sent as VIDEO).
                               -- No mixed video + image, no changes to the stored media type.
                               NOT EXISTS(SELECT 1 FROM post_media m WHERE m.post_id=p.id
                                   AND (m.public_url IS NULL OR btrim(m.public_url)=''
                                        OR m.media_type IS NULL OR m.media_type NOT IN ('IMAGE','VIDEO','REEL')
                                        OR (m.media_type='IMAGE' AND m.public_url !~* '^https://res[.]cloudinary[.]com/[^/?#]+/image/upload/[^?#]+([?][^#]*)?$')
                                        OR (m.media_type IN ('VIDEO','REEL') AND m.public_url !~* '^https://res[.]cloudinary[.]com/[^/?#]+/video/upload/[^?#]+([?][^#]*)?$')))
                               AND (SELECT COUNT(*) FROM post_media m WHERE m.post_id=p.id) <= 20
                               AND (NOT EXISTS(SELECT 1 FROM post_media m WHERE m.post_id=p.id
                                               AND m.media_type IN ('VIDEO','REEL'))
                                    OR (SELECT COUNT(*) FROM post_media m WHERE m.post_id=p.id) = 1)
                               AS media_ready
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
                        rs.getBoolean("media_ready"), "IMPORT"), req.threadsAccountId(), importId,
                req.fromRow() == null ? 2 : req.fromRow(),
                req.toRow() == null ? 10001 : req.toRow());
    }

    private Snapshot snapshot(UUID importId, Request req) {
        List<Source> sources = readSources(importId, req);

        List<Instant> occupied = occupiedSlots(req.threadsAccountId());

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

    // Workspace-wide plan; existing single-import API remains compatible.
    public record MultiRequest(List<UUID> importIds, UUID threadsAccountId,
                               LocalDate startDate, LocalTime startTime, Integer intervalMinutes,
                               Integer maxPer24Hours, Boolean dryRun, Boolean confirmLive,
                               String previewToken) {
    }

    public record MultiRow(UUID importId, int sourceRowNumber, UUID postId, String action,
                           OffsetDateTime scheduledAt, String note) {
    }

    public record MultiResult(UUID threadsAccountId, boolean dryRun, String previewToken,
                              int scanned, int eligible, int created, int alreadyPlanned,
                              int blocked, List<MultiRow> rows) {
    }

    private record ImportedSource(UUID importId, Source source) {
    }

    private record MultiSnapshot(String token, List<MultiRow> rows) {
    }

    private static final int MAX_IMPORTS = 100;
    private static final int MAX_SOURCE_ROWS = 10000;
    // Local planning ceiling, not a statement of the Meta account's remaining quota.
    @org.springframework.beans.factory.annotation.Value("${app.threads.multi-plan.max-per-24-hours:50}")
    private int planningCeiling = 50;

    public MultiResult planAll(UUID workspaceId, MultiRequest req) {
        access.admin(workspaceId);
        validateMulti(req);
        boolean preview = !Boolean.FALSE.equals(req.dryRun());
        if (!preview && (!Boolean.TRUE.equals(req.confirmLive()) || req.previewToken() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Explicit confirmation and previewToken required");
        }
        if (preview) return multiResult(req, buildMulti(workspaceId, req), true);
        // One transaction for the entire confirmed plan. Existing create() must use REQUIRED.
        return transactions.execute(tx -> {
            lockAccount(req.threadsAccountId());
            MultiSnapshot snapshot = buildMulti(workspaceId, req);
            if (!MessageDigest.isEqual(snapshot.token().getBytes(StandardCharsets.UTF_8),
                    req.previewToken().getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(HttpStatus.CONFLICT, "Preview expired or source/slots changed; preview again");
            }
            List<MultiRow> outcomes = new ArrayList<>();
            for (MultiRow row : snapshot.rows()) {
                if (!"READY_TO_CREATE".equals(row.action())) {
                    outcomes.add(row);
                    continue;
                }
                if (!row.scheduledAt().toInstant().isAfter(Instant.now().plusSeconds(60))) {
                    throw new ApiException(HttpStatus.CONFLICT, "Schedule too close; preview again");
                }
                publishingJobs.create(row.postId(), req.threadsAccountId(), row.scheduledAt());
                outcomes.add(new MultiRow(row.importId(), row.sourceRowNumber(), row.postId(),
                        "JOB_CREATED", row.scheduledAt(), "Scheduled; no direct Meta call; " + row.note()));
            }
            return multiResult(req, new MultiSnapshot(snapshot.token(), List.copyOf(outcomes)), false);
        });
    }

    private void validateMulti(MultiRequest req) {
        if (req == null || req.importIds() == null || req.importIds().isEmpty()
                || req.importIds().size() > MAX_IMPORTS || req.importIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(req.importIds()).size() != req.importIds().size()
                || req.threadsAccountId() == null || req.startDate() == null || req.startTime() == null
                || req.intervalMinutes() == null || req.intervalMinutes() < 1 || req.intervalMinutes() > 1440
                || req.maxPer24Hours() == null || req.maxPer24Hours() < 1
                || req.maxPer24Hours() > planningCeiling) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Select 1..100 unique imports, connected account, date/time, interval 1..1440 and maxPer24Hours 1.." + planningCeiling);
        }
        if (!ZonedDateTime.of(req.startDate(), req.startTime(), ZONE).toInstant().isAfter(Instant.now().plusSeconds(300))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Start time must be at least 5 minutes in the future");
        }
    }

    private void lockAccount(UUID accountId) {
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))",
                (rs, i) -> rs.getObject(1), "socialhub-threads-plan", accountId.toString());
    }

    private List<Instant> occupiedSlots(UUID accountId) {
        return db.query("""
                SELECT CASE WHEN status='PUBLISHED' THEN COALESCE(published_at, scheduled_at)
                            ELSE scheduled_at END AS slot
                FROM publishing_jobs WHERE social_account_id=?
                  AND status IN ('PENDING','PROCESSING','RETRY','PUBLISHED','NEEDS_REVIEW')
                  AND (CASE WHEN status='PUBLISHED' THEN COALESCE(published_at, scheduled_at) ELSE scheduled_at END) IS NOT NULL
                ORDER BY slot
                """, (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant(), accountId);
    }

    private MultiSnapshot buildMulti(UUID workspaceId, MultiRequest req) {
        List<ImportedSource> sources = new ArrayList<>();
        for (UUID importId : req.importIds()) {
            validateLinkage(workspaceId, importId, req.threadsAccountId());
            Request scoped = new Request(req.threadsAccountId(), req.startDate(), req.startTime(),
                    req.intervalMinutes(), 50, true, false, null, null, null);
            // readSources reads the entire import; maxRows only limits the legacy planner.
            List<Source> batch = recoverMissingDates(workspaceId, importId, readSources(importId, scoped));
            Integer actual = db.queryForObject("""
                    SELECT COUNT(*) FROM excel_import_rows r JOIN excel_imports e ON e.id=r.import_id
                    JOIN posts p ON p.id=r.post_id AND p.workspace_id=e.workspace_id
                    WHERE e.id=? AND e.workspace_id=? AND r.post_id IS NOT NULL
                      AND (r.processing_status IS NULL OR r.processing_status NOT LIKE 'SKIPPED_%')
                    """, Integer.class, importId, workspaceId);
            if (actual == null || actual != batch.size()) {
                throw new ApiException(HttpStatus.CONFLICT, "Source rows outside supported range; import=" + importId);
            }
            for (Source source : batch) sources.add(new ImportedSource(importId, source));
            if (sources.size() > MAX_SOURCE_ROWS)
                throw new ApiException(HttpStatus.BAD_REQUEST, "Select at most 10000 source rows per plan");
        }
        List<Instant> occupied = new ArrayList<>(occupiedSlots(req.threadsAccountId()));
        // Reject overdue/uncertain active jobs: a planned slot is not a delivery-rate guarantee.
        Boolean unsettled = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM publishing_jobs WHERE social_account_id=?
                  AND (status IN ('PROCESSING','NEEDS_REVIEW') OR
                    (status IN ('PENDING','RETRY') AND (scheduled_at IS NULL OR scheduled_at <= now()))))
                """, Boolean.class, req.threadsAccountId());
        if (Boolean.TRUE.equals(unsettled))
            throw new ApiException(HttpStatus.CONFLICT, "Resolve overdue or uncertain Threads jobs before planning more");
        Instant now = Instant.now();
        Instant earliest = now.plusSeconds(300);
        Instant start = ZonedDateTime.of(req.startDate(), req.startTime(), ZONE).toInstant();
        long intervalSeconds = req.intervalMinutes() * 60L;
        StringBuilder signature = new StringBuilder(workspaceId.toString()).append('|').append(req.importIds())
                .append('|').append(req.threadsAccountId()).append('|').append(start)
                .append('|').append(req.intervalMinutes()).append('|').append(req.maxPer24Hours());
        sources.forEach(s -> signature.append('|').append(s));
        List<MultiRow> planned = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        // Reserve immutable future slots FIRST so expired-date replacements cannot take them.
        Map<UUID, Instant> future = new HashMap<>();
        Map<UUID, String> futureBlocked = new HashMap<>();
        for (ImportedSource entry : sources) {
            Source src = entry.source();
            if (src.jobExists() || invalidSource(src) || !seen.add(src.postId())) continue;
            Instant original = ZonedDateTime.of(src.originalDate(), src.publishTime(), ZONE).toInstant();
            if (!original.isAfter(now)) continue;
            if (!original.isAfter(earliest) || !slotAllowed(original, occupied, req)) {
                futureBlocked.put(src.postId(), "Original future slot conflicts or exceeds local limit; date preserved" + dateNote(src));
            } else {
                future.put(src.postId(), original);
                occupied.add(original);
            }
        }
        seen.clear();
        Instant cursor = start;
        for (ImportedSource entry : sources) {
            UUID importId = entry.importId();
            Source src = entry.source();
            String action;
            String note;
            Instant due = null;
            if (!seen.add(src.postId())) {
                action = "BLOCKED";
                note = "Duplicate Post in selected sources";
            } else if (src.jobExists()) {
                action = "ALREADY_PLANNED";
                note = "Threads Job exists for this Post/account";
            } else if (invalidSource(src)) {
                action = "BLOCKED";
                note = invalidReason(src);
            } else if (futureBlocked.containsKey(src.postId())) {
                action = "BLOCKED";
                note = futureBlocked.get(src.postId());
            } else if (future.containsKey(src.postId())) {
                action = "READY_TO_CREATE";
                due = future.get(src.postId());
                note = "KEEP_ORIGINAL" + dateNote(src);
            } else {
                // One shared cursor across all imports, not a new cursor for each file.
                for (int attempt = 0; attempt < 100000; attempt++) {
                    Instant candidate = cursor;
                    cursor = cursor.plusSeconds(intervalSeconds);
                    if (candidate.isAfter(earliest) && slotAllowed(candidate, occupied, req)) {
                        due = candidate;
                        occupied.add(due);
                        break;
                    }
                }
                action = due == null ? "BLOCKED" : "READY_TO_CREATE";
                note = due == null ? "No available slot" : "RESCHEDULED; source data unchanged" + dateNote(src);
            }
            planned.add(new MultiRow(importId, src.row(), src.postId(), action,
                    due == null ? null : due.atZone(ZONE).toOffsetDateTime(), note));
        }
        planned.forEach(r -> signature.append('|').append(r));
        return new MultiSnapshot(sha256(signature.toString()), List.copyOf(planned));
    }

    // Read-only recovery for multi-import planning. Never pretend a current Sheet date
    // is the historical original date, and never change excel_import_rows.
    private record SheetDateMatch(int localRow, UUID postId, UUID connectionId,
                                  int sheetRow, String rawDate) {
    }

    private List<Source> recoverMissingDates(UUID workspaceId, UUID importId, List<Source> sources) {
        if (sources.stream().noneMatch(s -> s.originalDate() == null)) return sources;
        List<SheetDateMatch> matches = db.query("""
                SELECT r.source_row_number AS local_row, p.id AS post_id,
                       gs.connection_id, gs.source_row_number AS sheet_row, gs.source_publish_date
                FROM excel_import_rows r
                JOIN excel_imports e ON e.id=r.import_id AND e.workspace_id=?
                JOIN posts p ON p.id=r.post_id AND p.workspace_id=e.workspace_id
                JOIN google_sheet_import_links l ON l.import_id=e.id AND l.workspace_id=e.workspace_id
                   AND l.enabled=TRUE
                JOIN google_sheet_connections gc ON gc.id=l.connection_id AND gc.workspace_id=e.workspace_id
                   AND gc.sync_status='SYNCED'
                JOIN google_sheet_staged_rows gs ON gs.connection_id=l.connection_id
                   AND gs.present_in_sheet=TRUE AND gs.content=p.content
                   AND gs.source_row_number BETWEEN 2 AND 10001
                WHERE r.import_id=? AND r.source_publish_date IS NULL
                  AND (r.processing_status IS NULL OR r.processing_status NOT LIKE 'SKIPPED_%')
                  AND p.content IS NOT NULL AND btrim(p.content)<>''
                  AND NOT EXISTS (
                    SELECT 1 FROM google_sheet_staged_rows d
                    WHERE d.connection_id=gs.connection_id AND d.present_in_sheet=TRUE
                      AND d.content=gs.content AND d.source_row_number<>gs.source_row_number
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM excel_import_rows d JOIN posts dp ON dp.id=d.post_id
                       AND dp.workspace_id=e.workspace_id
                    WHERE d.import_id=e.id AND d.id<>r.id AND dp.content=p.content
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM google_sheet_import_links ol
                    JOIN excel_import_rows obr ON obr.import_id=ol.import_id
                    JOIN posts op ON op.id=obr.post_id AND op.workspace_id=e.workspace_id
                    WHERE ol.connection_id=l.connection_id AND ol.workspace_id=e.workspace_id
                      AND ol.enabled=TRUE AND ol.import_id<>e.id AND op.content=p.content
                  )
                """, (rs, i) -> new SheetDateMatch(rs.getInt("local_row"),
                rs.getObject("post_id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getInt("sheet_row"), rs.getString("source_publish_date")), workspaceId, importId);
        Map<Integer, List<SheetDateMatch>> byRow = new HashMap<>();
        for (SheetDateMatch m : matches) byRow.computeIfAbsent(m.localRow(), k -> new ArrayList<>()).add(m);
        return sources.stream().map(src -> {
            if (src.originalDate() != null) return src;
            List<SheetDateMatch> found = byRow.getOrDefault(src.row(), List.of());
            if (found.size() != 1 || !Objects.equals(found.getFirst().postId(), src.postId())) return src;
            SheetDateMatch match = found.getFirst();
            LocalDate date = parseCurrentSheetDate(match.rawDate());
            if (date == null) return src;
            return new Source(src.row(), src.postId(), src.content(), src.postStatus(), src.approval(),
                    date, src.publishTime(), src.jobExists(), src.mediaReady(),
                    "CURRENT_SHEET:" + match.connectionId() + ":" + match.sheetRow() + ":" + match.rawDate());
        }).toList();
    }

    static LocalDate parseCurrentSheetDate(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (!value.matches("(?:\\d{1,2}/\\d{1,2}/\\d{4}|\\d{4}-\\d{2}-\\d{2})(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?"))
            return null;
        String date = value.split("\\s+")[0];
        try {
            return date.contains("/") ? LocalDate.parse(date,
                    java.time.format.DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ROOT)
                            .withResolverStyle(java.time.format.ResolverStyle.STRICT))
                    : LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    private String dateNote(Source s) {
        return s.dateOrigin().startsWith("CURRENT_SHEET:")
                ? "; date from current synced Sheet (not historical original); " + s.dateOrigin()
                : "";
    }

    private boolean invalidSource(Source s) {
        return !invalidReason(s).isEmpty();
    }

    private String invalidReason(Source s) {
        List<String> reasons = new ArrayList<>();
        if (s.originalDate() == null) reasons.add("MISSING_SOURCE_DATE: no valid unique linked Sheet date");
        if (s.publishTime() == null) reasons.add("MISSING_PUBLISH_TIME");
        if (s.content() == null || s.content().isBlank()) reasons.add("EMPTY_CONTENT");
        else if (s.content().length() > 500) reasons.add("CONTENT_TOO_LONG");
        if (!"APPROVED".equals(s.approval())) reasons.add("NOT_APPROVED");
        if (!("READY".equals(s.postStatus()) || "SCHEDULED".equals(s.postStatus())))
            reasons.add("INVALID_POST_STATUS: " + s.postStatus());
        if (!s.mediaReady())
            reasons.add("INCOMPATIBLE_MEDIA: expected Cloudinary IMAGE(s) or one VIDEO/REEL with a video upload URL; mixed media unsupported");
        return String.join("; ", reasons);
    }

    // Test every rolling 24-hour window containing the candidate, not just calendar dates.
    static boolean rollingAllowed(Instant candidate, List<Instant> occupied, int cap) {
        long window = 86400;
        List<Instant> nearby = new ArrayList<>();
        for (Instant t : occupied) {
            if (t.isAfter(candidate.minusSeconds(window)) && t.isBefore(candidate.plusSeconds(window))) nearby.add(t);
        }
        nearby.add(candidate);
        nearby.sort(Comparator.naturalOrder());
        int left = 0;
        for (int right = 0; right < nearby.size(); right++) {
            while (!nearby.get(left).isAfter(nearby.get(right).minusSeconds(window))) left++;
            if (right - left + 1 > cap && !nearby.get(left).isAfter(candidate) && !nearby.get(right).isBefore(candidate))
                return false;
        }
        return true;
    }

    private boolean slotAllowed(Instant time, List<Instant> occupied, MultiRequest req) {
        return !collides(time, occupied, req.intervalMinutes()) && rollingAllowed(time, occupied, req.maxPer24Hours());
    }

    private MultiResult multiResult(MultiRequest req, MultiSnapshot snapshot, boolean preview) {
        int eligible = 0, created = 0, already = 0, blocked = 0;
        for (MultiRow row : snapshot.rows())
            switch (row.action()) {
                case "READY_TO_CREATE" -> eligible++;
                case "JOB_CREATED" -> {
                    eligible++;
                    created++;
                }
                case "ALREADY_PLANNED" -> already++;
                default -> blocked++;
            }
        return new MultiResult(req.threadsAccountId(), preview, snapshot.token(), snapshot.rows().size(),
                eligible, created, already, blocked, snapshot.rows());
    }

    private static final class AlreadyExists extends RuntimeException {
    }
}
