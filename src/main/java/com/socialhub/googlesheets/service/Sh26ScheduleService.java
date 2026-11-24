package com.socialhub.googlesheets.service;

import com.socialhub.common.ApiException;
import com.socialhub.excelimport.service.implement.ExcelAutoPublishService;
import com.socialhub.googlesheets.dto.Sh26BulkDto;
import com.socialhub.googlesheets.dto.Sh26ScheduleDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Reuses the existing per-import AutoPlan/Batch (and its job uniqueness, collision
 * detection, approval and per-row transactional behavior). No second publisher.
 */
@Service
@RequiredArgsConstructor
public class Sh26ScheduleService {
    private final Sh26BulkDraftService drafts;
    private final ExcelAutoPublishService planner;
    private final JdbcTemplate db;

    @Value("${app.publisher-enabled:${PUBLISHER_ENABLED:false}}")
    private boolean publisherEnabled;
    @Value("${FACEBOOK_PUBLISHING_MODE:MOCK}")
    private String mode;
    @Value("${FACEBOOK_MULTI_JOB_ENABLED:false}")
    private boolean multiJobEnabled;
    @Value("${FACEBOOK_BULK_ENABLED:false}")
    private boolean bulkEnabled;
    @Value("${app.facebook.video-publishing-enabled:false}")
    private boolean videoEnabled;
    @Value("${app.facebook.reels-publishing-enabled:false}")
    private boolean reelsEnabled;
    @Value("${app.excel.auto-publish-enabled:false}")
    private boolean excelAutoPublishEnabled;

    /**
     * Read-only reconciliation of the SH26 group imports, never queries SH-T10 by prefix.
     */
    public List<Sh26ScheduleDto.JobStatus> jobs(UUID workspaceId) {
        List<Sh26ScheduleDto.JobStatus> grouped = db.query("""
                SELECT REPLACE(e.source_sheet_name,'SH26_','') AS grp,
                  COUNT(j.id) AS total,
                  COUNT(j.id) FILTER (WHERE j.status='PENDING') AS pending,
                  COUNT(j.id) FILTER (WHERE j.status='PUBLISHED') AS published,
                  COUNT(j.id) FILTER (WHERE j.status='FAILED') AS failed,
                  COUNT(j.id) FILTER (WHERE j.status='PROCESSING') AS processing,
                  COUNT(j.id) FILTER (WHERE j.status NOT IN ('PENDING','PUBLISHED','FAILED','PROCESSING')) AS other
                FROM excel_imports e
                LEFT JOIN posts p ON p.import_id=e.id
                LEFT JOIN publishing_jobs j ON j.post_id=p.id AND j.social_account_id=e.social_account_id
                WHERE e.workspace_id=? AND LEFT(e.source_sheet_name,6)='SH26_A'
                GROUP BY e.source_sheet_name ORDER BY e.source_sheet_name
                """, (rs, row) -> new Sh26ScheduleDto.JobStatus(rs.getString(1),
                rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)), workspaceId);
        // Display the historical P001 Job separately but count it in A01's overall SH26 inventory.
        List<String> oldStatuses = db.queryForList("""
                SELECT j.status FROM publishing_jobs j
                JOIN posts p ON p.id=j.post_id
                WHERE p.workspace_id=? AND p.content LIKE '%SH26-A01-P001%'
                  AND NOT EXISTS (SELECT 1 FROM excel_imports e
                     WHERE e.id=p.import_id AND e.source_sheet_name='SH26_A01')
                """, String.class, workspaceId);
        if (oldStatuses.size() == 1) {
            String status = oldStatuses.getFirst();
            List<Sh26ScheduleDto.JobStatus> result = new ArrayList<>();
            for (Sh26ScheduleDto.JobStatus g : grouped) {
                if (!"A01".equals(g.group())) {
                    result.add(g);
                    continue;
                }
                result.add(new Sh26ScheduleDto.JobStatus("A01", g.totalJobs() + 1,
                        g.pending() + ("PENDING".equals(status) ? 1 : 0),
                        g.published() + ("PUBLISHED".equals(status) ? 1 : 0),
                        g.failed() + ("FAILED".equals(status) ? 1 : 0),
                        g.processing() + ("PROCESSING".equals(status) ? 1 : 0),
                        g.other() + (Set.of("PENDING", "PUBLISHED", "FAILED", "PROCESSING").contains(status) ? 0 : 1)));
            }
            return result;
        }
        return grouped;
    }

    public Sh26ScheduleDto.Capability capabilities(UUID workspaceId) {
        boolean live = "LIVE".equalsIgnoreCase(mode);
        boolean ready = publisherEnabled && live && multiJobEnabled && !bulkEnabled && videoEnabled && reelsEnabled && excelAutoPublishEnabled;
        return new Sh26ScheduleDto.Capability(publisherEnabled, live, multiJobEnabled,
                videoEnabled, reelsEnabled, bulkEnabled, excelAutoPublishEnabled, ready, earliestNonOverlappingDate(workspaceId).toString(),
                ready ? "Runtime gates allow preview-confirm. Actual Meta permissions are NOT verified."
                        : "Cannot confirm SH26 mixed media jobs: check LIVE, multiJob, video, reels, bulk=false flags.");
    }

    public Sh26ScheduleDto.Preview preview(UUID workspaceId, Sh26ScheduleDto.Settings s) {
        List<Sh26BulkDto.Imported> imports = exactImports(workspaceId);
        checkNonOverlapping(workspaceId, s);
        List<Sh26ScheduleDto.Group> groups = new ArrayList<>();
        int selected = 0, blocked = 0, already = 0;
        for (Sh26BulkDto.Imported item : imports) {
            var plan = planner.planBatch(workspaceId, item.importId(), batch(s, true, null));
            groups.add(new Sh26ScheduleDto.Group(item.group(), item.importId(), plan.selectedRows(),
                    plan.rescheduled(), plan.keepOriginal(), plan.blocked(), plan.alreadyPlanned(),
                    plan.previewToken(), 0, "PREVIEW", "No publishing jobs created"));
            selected += plan.selectedRows();
            blocked += plan.blocked();
            already += plan.alreadyPlanned();
        }
        int expectedTotal = imports.stream().mapToInt(this::expectedDrafts).sum();
        if (selected != expectedTotal || blocked != 0 || already != 0)
            throw new ApiException(HttpStatus.CONFLICT,
                    "SH26 new Job preview is not complete: expected " + expectedTotal + " eligible rows");
        return new Sh26ScheduleDto.Preview(capabilities(workspaceId), selected, blocked,
                already, List.copyOf(groups));
    }

    public Sh26ScheduleDto.Result confirm(UUID workspaceId, Sh26ScheduleDto.Confirm request) {
        if (request == null || !"SCHEDULE_SH26_1000_LIVE".equals(request.confirmation()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Explicit SCHEDULE_SH26_1000_LIVE confirmation required");
        if (!capabilities(workspaceId).canConfirm())
            throw new ApiException(HttpStatus.CONFLICT,
                    "VIDEO/REEL LIVE publishing is disabled or Publisher worker flags are not ready; no Jobs created");
        List<Sh26BulkDto.Imported> imports = exactImports(workspaceId);
        checkNonOverlapping(workspaceId, request.settings());
        if (request.tokens() == null || request.tokens().size() != 10)
            throw new ApiException(HttpStatus.BAD_REQUEST, "10 preview tokens required");
        Map<String, Sh26ScheduleDto.GroupToken> supplied = new HashMap<>();
        for (var token : request.tokens()) {
            if (token == null || token.group() == null || supplied.putIfAbsent(token.group(), token) != null)
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid or duplicate group token");
        }
        // Verify all ten previews BEFORE any job can be created.
        for (Sh26BulkDto.Imported item : imports) {
            var token = supplied.get(item.group());
            if (token == null || !item.importId().equals(token.importId()) || token.previewToken() == null)
                throw new ApiException(HttpStatus.CONFLICT, "Import mapping changed; preview again");
            var current = planner.planBatch(workspaceId, item.importId(), batch(request.settings(), true, null));
            if (!MessageDigest.isEqual(token.previewToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    current.previewToken().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                throw new ApiException(HttpStatus.CONFLICT, "A SH26 plan changed; preview again before confirming");
            int expected = expectedDrafts(item);
            if (current.selectedRows() != expected || current.blocked() > 0 || current.alreadyPlanned() > 0)
                throw new ApiException(HttpStatus.CONFLICT,
                        "Expected " + expected + " unplanned eligible rows for " + item.group() + "; preview again");
        }
        int created = 0, blocked = 0, already = 0;
        List<Sh26ScheduleDto.Group> results = new ArrayList<>();
        for (Sh26BulkDto.Imported item : imports) {
            var token = supplied.get(item.group());
            try {
                // Existing planner locks the account, checks conflicts, prepares media,
                // approves posts and creates jobs per row. Partial failures are reported.
                var plan = planner.planBatch(workspaceId, item.importId(),
                        batch(request.settings(), false, token.previewToken()));
                results.add(new Sh26ScheduleDto.Group(item.group(), item.importId(),
                        plan.selectedRows(), plan.rescheduled(), plan.keepOriginal(),
                        plan.blocked(), plan.alreadyPlanned(), plan.previewToken(),
                        plan.createdJobs(), plan.blocked() == 0 ? "COMPLETED" : "PARTIAL",
                        String.join("; ", plan.errors().stream().limit(3).toList())));
                created += plan.createdJobs();
                blocked += plan.blocked();
                already += plan.alreadyPlanned();
            } catch (RuntimeException failure) {
                results.add(new Sh26ScheduleDto.Group(item.group(), item.importId(), 0, 0, 0,
                        0, 0, token.previewToken(), 0, "ERROR",
                        failure.getMessage() == null ? "Unknown planner error" : failure.getMessage()));
                // STOP; never re-issue completed groups. Read status and re-preview before resuming.
                break;
            }
        }
        return new Sh26ScheduleDto.Result(created, blocked, already, List.copyOf(results),
                "Inspect group results, Job IDs and real Facebook Attempts. Never blindly retry after timeout.");
    }

    /**
     * Prevent the SH26 schedule from increasing the daily posting volume on pages
     * that still have SH-T10 pending jobs. Date must be AFTER the last due day.
     */
    private void checkNonOverlapping(UUID workspaceId, Sh26ScheduleDto.Settings s) {
        if (s == null || s.startDate() == null || s.startDate().isBefore(earliestNonOverlappingDate(workspaceId)))
            throw new ApiException(HttpStatus.CONFLICT,
                    "To avoid overlapping SH-T10 and SH26 on the same Pages, choose startDate >= "
                            + earliestNonOverlappingDate(workspaceId));
    }

    private LocalDate earliestNonOverlappingDate(UUID workspaceId) {
        List<Sh26BulkDto.Imported> existing = drafts.existingImports(workspaceId);
        LocalDate result = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(1);
        for (Sh26BulkDto.Imported entry : existing) {
            Instant last = db.queryForObject("""
                    SELECT MAX(j.scheduled_at) FROM publishing_jobs j
                    WHERE j.social_account_id=? AND j.status IN ('PENDING','PROCESSING','RETRY')
                      AND j.scheduled_at IS NOT NULL
                    """, (rs, idx) -> {
                java.sql.Timestamp stamp = rs.getTimestamp(1);
                return stamp == null ? null : stamp.toInstant();
            }, entry.socialAccountId());
            if (last != null) {
                LocalDate next = last.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().plusDays(1);
                if (next.isAfter(result)) result = next;
            }
        }
        return result;
    }

    private List<Sh26BulkDto.Imported> exactImports(UUID workspaceId) {
        List<Sh26BulkDto.Imported> found = drafts.existingImports(workspaceId);
        Set<String> expectedGroups = Set.of("A01", "A02", "A03", "A04", "A05",
                "A06", "A07", "A08", "A09", "A10");
        if (found.size() != 10
                || !found.stream().map(Sh26BulkDto.Imported::group).collect(java.util.stream.Collectors.toSet()).equals(expectedGroups)
                || found.stream().map(Sh26BulkDto.Imported::group).distinct().count() != 10
                || found.stream().map(Sh26BulkDto.Imported::socialAccountId).distinct().count() != 10
                || found.stream().anyMatch(i -> i.skipped() != 0)) {
            throw new ApiException(HttpStatus.CONFLICT, "SH26 requires ten distinct imports; do not reimport");
        }
        // A01 has 99 NEW Posts when its original P001/Job is safely reused.
        Sh26BulkDto.Imported a01 = found.stream().filter(i -> "A01".equals(i.group()))
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "SH26 A01 missing"));
        if (a01.createdDrafts() == 99 && !drafts.hasReusableP001Job(workspaceId, a01.socialAccountId()))
            throw new ApiException(HttpStatus.CONFLICT, "A01 P001 existing Job is missing or mapped to wrong Page");
        if (a01.createdDrafts() != 99 && a01.createdDrafts() != 100)
            throw new ApiException(HttpStatus.CONFLICT, "A01 must contain 99 or 100 new drafts");
        for (Sh26BulkDto.Imported item : found) {
            int expected = expectedDrafts(item);
            if (item.createdDrafts() != expected)
                throw new ApiException(HttpStatus.CONFLICT, "Incorrect SH26 draft count for " + item.group());
            Integer count = db.queryForObject("SELECT count(*) FROM posts WHERE import_id=?", Integer.class, item.importId());
            if (count == null || count != expected)
                throw new ApiException(HttpStatus.CONFLICT, "SH26 imported Posts missing for " + item.group());
        }
        return found.stream().sorted(Comparator.comparing(Sh26BulkDto.Imported::group)).toList();
    }

    private int expectedDrafts(Sh26BulkDto.Imported group) {
        if ("A01".equals(group.group())) return group.createdDrafts() == 99 ? 99 : 100;
        return 100;
    }

    private static ExcelAutoPublishService.BatchPlanRequest batch(Sh26ScheduleDto.Settings s,
                                                                  boolean preview, String token) {
        if (s == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Schedule settings required");
        return new ExcelAutoPublishService.BatchPlanRequest(
                "KEEP_FUTURE_RESCHEDULE_EXPIRED", s.startDate(), s.startTime(),
                s.intervalMinutes(), s.maxPostsPerDay(), "Asia/Ho_Chi_Minh", 100,
                preview, !preview, token);
    }
}
