package com.socialhub.excelimport.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.excelimport.entity.ExcelImport;
import com.socialhub.excelimport.entity.ExcelImportRow;
import com.socialhub.excelimport.repository.ExcelImportRepository;
import com.socialhub.excelimport.repository.ExcelImportRowRepository;
import com.socialhub.media.service.implement.ExcelImportMediaBatchService;
import com.socialhub.post.entity.Post;
import com.socialhub.postapproval.service.interfaces.PostApprovalService;
import com.socialhub.postmedia.repository.PostMediaRepository;
import com.socialhub.publishingjob.service.interfaces.PublishingJobService;
import com.socialhub.publishingjob.repository.PublishingJobRepository;
import com.socialhub.security.AccessService;
import com.socialhub.socialaccount.entity.SocialAccount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.*;
import java.util.*;

/**
 * Excel planning. The existing outbox/worker is responsible for actual delivery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelAutoPublishService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MEDIA_BATCH_SIZE = 5;

    @Value("${app.excel.auto-publish-enabled:false}")
    private boolean enabled;

    private final AccessService access;
    private final ExcelImportRepository imports;
    private final ExcelImportRowRepository rows;
    private final PostMediaRepository media;
    private final PublishingJobRepository jobs;
    private final ExcelImportMediaBatchService mediaBatch;
    private final PostApprovalService approvals;
    private final PublishingJobService publishingJobs;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;

    public record Result(UUID importId, int planned, int alreadyPlanned, int blocked, List<String> errors) {
    }

    public void validateFile(MultipartFile file) {
        requireEnabled();
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Excel file is empty");
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Excel automatic publishing is disabled (APP_EXCEL_AUTO_PUBLISH_ENABLED=false)");
        }
    }

    private ExcelImport requireBatch(UUID workspaceId, UUID importId) {
        access.admin(workspaceId);
        requireEnabled();
        ExcelImport batch = imports.findByIdAndWorkspaceId(importId, workspaceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Excel import not found"));
        if (batch.getSocialAccount() == null || batch.getPublishTime() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Import requires a social account and publish time");
        }
        return batch;
    }

    /**
     * Existing whole-import API: never changes an expired source date.
     */
    public Result plan(UUID workspaceId, UUID importId) {
        ExcelImport batch = requireBatch(workspaceId, importId);
        List<ExcelImportRow> importedRows = rows.findByExcelImportIdOrderBySourceRowNumberAsc(importId);

        // Avoid preparing media for an import where every eligible date is already expired.
        boolean hasFutureRows = importedRows.stream().anyMatch(r ->
                r.getPost() != null
                        && (r.getProcessingStatus() == null
                        || (!r.getProcessingStatus().startsWith("SKIPPED_")
                        && !"PUBLISHED".equals(r.getProcessingStatus())))
                        && r.getSourcePublishDate() != null
                        && ZonedDateTime.of(r.getSourcePublishDate(), batch.getPublishTime(), ZONE)
                        .toInstant().isAfter(Instant.now().plusSeconds(60)));

        if (hasFutureRows) try {
            Integer cursor = null;
            int batches = 0;
            int maxBatches = importedRows.size() + 1;
            while (true) {
                var result = mediaBatch.process(workspaceId, importId, MEDIA_BATCH_SIZE, cursor);
                batches++;
                if (!result.hasMore()) break;
                Integer next = result.nextAfterRowNumber();
                if (next == null || (cursor != null && next <= cursor)) {
                    throw new IllegalStateException("Excel media cursor did not advance");
                }
                if (batches >= maxBatches) {
                    throw new IllegalStateException("Excel media batch safety bound exceeded");
                }
                cursor = next;
            }
        } catch (RuntimeException ex) {
            log.error("[EXCEL AUTO] Media batch failed import={}", importId, ex);
        }

        int planned = 0, already = 0, blocked = 0, expired = 0;
        List<String> errors = new ArrayList<>();
        UUID accountId = batch.getSocialAccount().getId();
        for (ExcelImportRow source : importedRows) {
            String state = source.getProcessingStatus();
            if (source.getPost() == null || "PUBLISHED".equals(state)
                    || (state != null && state.startsWith("SKIPPED_"))) continue;
            UUID postId = source.getPost().getId();
            try {
                if (jobs.existsByPostIdAndSocialAccountId(postId, accountId)) {
                    setRowStatus(source.getId(), "AUTO_SCHEDULED", null);
                    already++;
                    continue;
                }
                if (source.getSourcePublishDate() == null) {
                    throw new IllegalStateException("Missing or invalid Ngày đăng");
                }
                ZonedDateTime at = ZonedDateTime.of(source.getSourcePublishDate(), batch.getPublishTime(), ZONE);
                if (!at.toInstant().isAfter(Instant.now().plusSeconds(60))) {
                    // Expected business condition, not an exception. Keep the original Excel date.
                    setRowStatus(source.getId(), "AUTO_BLOCKED", "Original scheduled date is in the past; use auto-plan/one to reschedule");
                    blocked++;
                    expired++;
                    continue;
                }
                validatePostAndMedia(source.getPost(), batch);
                // Each row is transactional; a failing job creation rolls back approval and Post update
                // provided downstream services use the ambient transaction (Propagation.REQUIRED).
                String outcome = scheduleRow(workspaceId, source.getId(), postId, accountId, at);
                if ("ALREADY".equals(outcome)) already++;
                else planned++;
            } catch (RuntimeException ex) {
                String reason = shortReason(ex);
                log.warn("[EXCEL AUTO] blocked import={} row={} reason={}",
                        importId, source.getSourceRowNumber(), reason);
                setRowStatus(source.getId(), "AUTO_BLOCKED", reason);
                blocked++;
                errors.add("row " + source.getSourceRowNumber() + ": " + reason);
            }
        }
        if (expired > 0) {
            errors.add("Skipped " + expired + " row(s) with expired Excel dates; use auto-plan/one to select a new future date");
        }
        log.info("[EXCEL AUTO] import={} planned={} existing={} blocked={} expired={}",
                importId, planned, already, blocked, expired);
        return new Result(importId, planned, already, blocked, errors);
    }

    /**
     * Explicit one-row LIVE retry, with a new date/time. Does not edit the original Excel date.
     */
    public Result planOne(UUID workspaceId, UUID importId, int sourceRowNumber,
                          OffsetDateTime publishAt, boolean confirmLive) {
        if (!confirmLive) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "confirmLive must be true");
        }
        if (sourceRowNumber <= 0 || publishAt == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sourceRowNumber and publishAt are required");
        }
        ExcelImport batch = requireBatch(workspaceId, importId);
        ZonedDateTime at = publishAt.atZoneSameInstant(ZONE);
        ensureFuture(at.toInstant());
        UUID accountId = batch.getSocialAccount().getId();

        ExcelImportRow selected = rows.findByExcelImportIdOrderBySourceRowNumberAsc(importId)
                .stream()
                .filter(r -> r.getSourceRowNumber() == sourceRowNumber)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Excel source row not found"));
        if ("PUBLISHED".equals(selected.getProcessingStatus())
                || (selected.getProcessingStatus() != null
                && selected.getProcessingStatus().startsWith("SKIPPED_"))) {
            throw new ApiException(HttpStatus.CONFLICT, "Already-published Excel row cannot be rescheduled");
        }
        if (selected.getPost() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Excel row has no Post");
        }
        UUID postId = selected.getPost().getId();
        if (jobs.existsByPostIdAndSocialAccountId(postId, accountId)) {
            return new Result(importId, 0, 1, 0, List.of());
        }

        // Prepare only this source row's images before scheduling.
        // This may download from Google Drive and upload to Cloudinary.
        try {
            var prepared = mediaBatch.processOne(workspaceId, importId, sourceRowNumber);
            if (prepared.failed() > 0) {
                String detail = prepared.media().stream()
                        .filter(item -> "FAILED".equals(item.status()))
                        .map(item -> item.message())
                        .filter(Objects::nonNull)
                        .distinct()
                        .limit(2)
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("unknown upload error");
                throw new IllegalStateException("Media preparation failed: " + detail);
            }
            validatePostAndMedia(selected.getPost(), batch);
            String outcome = scheduleRow(workspaceId, selected.getId(), postId, accountId, at);
            return "ALREADY".equals(outcome)
                    ? new Result(importId, 0, 1, 0, List.of())
                    : new Result(importId, 1, 0, 0, List.of());
        } catch (RuntimeException ex) {
            String reason = shortReason(ex);
            log.warn("[EXCEL AUTO] one-row blocked import={} sourceRow={} post={} reason={}",
                    importId, sourceRowNumber, postId, reason);
            setRowStatus(selected.getId(), "AUTO_BLOCKED", reason);
            return new Result(importId, 0, 0, 1,
                    List.of("row " + sourceRowNumber + ": " + reason));
        }
    }

    // -------------------------------------------------------------------------
    // Batch Auto Plan: preview is strictly read-only; confirmation schedules Jobs.
    // This API deliberately does NOT activate Facebook LIVE Bulk Runs.
    // -------------------------------------------------------------------------

    public record BatchPlanRequest(
            String mode,
            LocalDate startDate,
            LocalTime startTime,
            Integer intervalMinutes,
            Integer maxPostsPerDay,
            String timezone,
            Integer maxRows,
            Boolean dryRun,
            Boolean confirmLive,
            String previewToken
    ) {
    }

    public record BatchPlanRow(
            UUID excelRowId,
            int sourceRowNumber,
            UUID postId,
            String action,
            OffsetDateTime scheduledAt,
            boolean mediaReady,
            boolean automaticApprovalRequired,
            String message
    ) {
    }

    public record BatchPlanResult(
            UUID importId,
            UUID socialAccountId,
            boolean dryRun,
            String previewToken,
            int selectedRows,
            int keepOriginal,
            int rescheduled,
            int alreadyPlanned,
            int skipped,
            int blocked,
            int notSelected,
            int createdJobs,
            List<BatchPlanRow> rows,
            List<String> errors
    ) {
    }

    private record BatchConfig(
            LocalDate startDate,
            LocalTime startTime,
            int intervalMinutes,
            int maxPostsPerDay,
            int maxRows
    ) {
    }

    private record BatchSnapshot(BatchPlanResult result, UUID accountId) {
    }

    /**
     * Two-step API: dryRun=true performs no writes and makes no external calls.
     * The returned previewToken must be supplied unchanged when confirming.
     * Even after confirmation this method only schedules Jobs: it does not start
     * any Facebook Bulk Run or make requests to Meta.
     */
    public BatchPlanResult planBatch(UUID workspaceId, UUID importId, BatchPlanRequest request) {
        BatchConfig config = validateBatchConfig(request);
        BatchSnapshot snapshot = Objects.requireNonNull(transactions.execute(tx ->
                createBatchSnapshot(workspaceId, importId, config)));
        BatchPlanResult preview = snapshot.result();

        if (request.dryRun() == null || request.dryRun()) {
            return preview;
        }
        if (!Boolean.TRUE.equals(request.confirmLive())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "confirmLive=true is required to create LIVE publishing jobs");
        }
        if (request.previewToken() == null
                || !java.security.MessageDigest.isEqual(
                request.previewToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                preview.previewToken().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Preview has changed. Run dryRun=true again and use its new previewToken");
        }

        List<BatchPlanRow> actual = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int created = 0;
        for (BatchPlanRow row : preview.rows()) {
            if (!"KEEP_ORIGINAL".equals(row.action())
                    && !"RESCHEDULED".equals(row.action())) {
                actual.add(row);
                continue;
            }
            try {
                // This is the only part that may access Google Drive / Cloudinary.
                // Never performed during preview.
                var processed = mediaBatch.processOne(workspaceId, importId, row.sourceRowNumber());
                if (processed.failed() > 0) {
                    throw new IllegalStateException(
                            "Media preparation failed for Excel row " + row.sourceRowNumber());
                }
                // Read current Post and account inside a transaction. Avoid lazy-proxy
                // access across transactions and verify media again after preparation.
                transactions.executeWithoutResult(tx -> {
                    ExcelImport batch = requireBatch(workspaceId, importId);
                    Post post = entityManager.find(Post.class, row.postId());
                    if (post == null || !workspaceId.equals(post.getWorkspace().getId())) {
                        throw new IllegalStateException("Post is no longer in this workspace");
                    }
                    validatePostAndMedia(post, batch);
                });
                String outcome = scheduleRow(workspaceId,
                        row.excelRowId(),
                        row.postId(), snapshot.accountId(),
                        row.scheduledAt().atZoneSameInstant(ZONE), config.intervalMinutes());
                if ("PLANNED".equals(outcome)) {
                    created++;
                    actual.add(new BatchPlanRow(row.excelRowId(), row.sourceRowNumber(), row.postId(),
                            "JOB_CREATED", row.scheduledAt(), true,
                            false, "Publishing Job created; not yet linked to a LIVE Bulk Run"));
                } else {
                    actual.add(new BatchPlanRow(row.excelRowId(), row.sourceRowNumber(), row.postId(),
                            "ALREADY_PLANNED", row.scheduledAt(), true, false,
                            "An existing Job for this Post and account was found"));
                }
            } catch (RuntimeException ex) {
                String reason = shortReason(ex);
                log.warn("[EXCEL BATCH] blocked import={} row={} reason={}",
                        importId, row.sourceRowNumber(), reason);
                errors.add("row " + row.sourceRowNumber() + ": " + reason);
                // Per-row failure must not erase the existing Post or its Excel source date.
                try {
                    UUID rowId = row.excelRowId();
                    setRowStatus(rowId, "AUTO_BLOCKED", reason);
                } catch (RuntimeException stateError) {
                    log.warn("[EXCEL BATCH] failed to record block for row={}",
                            row.sourceRowNumber(), stateError);
                }
                actual.add(new BatchPlanRow(row.excelRowId(), row.sourceRowNumber(), row.postId(),
                        "BLOCKED", row.scheduledAt(), false,
                        row.automaticApprovalRequired(), reason));
            }
        }

        int actualBlocked = (int) actual.stream().filter(r -> "BLOCKED".equals(r.action())).count();
        int actualAlready = (int) actual.stream().filter(r -> "ALREADY_PLANNED".equals(r.action())).count();
        return new BatchPlanResult(importId, snapshot.accountId(), false,
                preview.previewToken(), preview.selectedRows(), preview.keepOriginal(),
                preview.rescheduled(), actualAlready, preview.skipped(), actualBlocked,
                preview.notSelected(), created, List.copyOf(actual), List.copyOf(errors));
    }

    private BatchConfig validateBatchConfig(BatchPlanRequest request) {
        if (request == null || request.startDate() == null || request.startTime() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "startDate and startTime are required");
        }
        if (request.mode() != null
                && !"KEEP_FUTURE_RESCHEDULE_EXPIRED".equals(request.mode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported batch mode");
        }
        if (request.timezone() != null && !ZONE.getId().equals(request.timezone())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Only timezone Asia/Ho_Chi_Minh is supported for this Excel import");
        }
        int interval = request.intervalMinutes() == null ? 30 : request.intervalMinutes();
        int perDay = request.maxPostsPerDay() == null ? 10 : request.maxPostsPerDay();
        // Deliberately default to one LIVE job per confirmation for initial testing.
        int maxRows = request.maxRows() == null ? 1 : request.maxRows();
        if (interval < 1 || interval > 1440
                || perDay < 1 || perDay > 100
                || maxRows < 1 || maxRows > 500) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "intervalMinutes=1..1440, maxPostsPerDay=1..100, maxRows=1..500 required"
            );
        }
        long lastMinute = request.startTime().toSecondOfDay() / 60L
                + (long) (perDay - 1) * interval;
        if (lastMinute > 23L * 60 + 59) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The final daily slot would cross midnight; reduce maxPostsPerDay or intervalMinutes");
        }
        Instant first = ZonedDateTime.of(request.startDate(), request.startTime(), ZONE).toInstant();
        if (!first.isAfter(Instant.now().plusSeconds(300))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The first automatic slot must be more than 5 minutes in the future");
        }
        return new BatchConfig(request.startDate(), request.startTime(), interval, perDay, maxRows);
    }

    private BatchSnapshot createBatchSnapshot(UUID workspaceId, UUID importId, BatchConfig cfg) {
        ExcelImport batch = requireBatch(workspaceId, importId);
        if (!"CONNECTED".equals(batch.getSocialAccount().getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Import social account is not CONNECTED");
        }
        UUID accountId = batch.getSocialAccount().getId();
        String platform = batch.getSocialAccount().getPlatform();
        if (!Set.of("FACEBOOK_PAGE", "THREADS").contains(platform)) {
            throw new ApiException(HttpStatus.CONFLICT, "Unsupported import social account platform");
        }
        List<ExcelImportRow> sources = rows.findByExcelImportIdOrderBySourceRowNumberAsc(importId);
        // Reserve already scheduled times for this same account. No mutations.
        List<OffsetDateTime> activeTimes = entityManager.createQuery("""
                        SELECT j.scheduledAt FROM PublishingJob j
                        WHERE j.socialAccount.id = :accountId
                          AND j.status IN ('PENDING', 'PROCESSING', 'RETRY')
                          AND j.scheduledAt IS NOT NULL
                        ORDER BY j.scheduledAt
                        """, OffsetDateTime.class)
                .setParameter("accountId", accountId)
                .getResultList();
        List<Instant> reserved = new ArrayList<>();
        for (OffsetDateTime at : activeTimes) reserved.add(at.toInstant());

        Instant earliestAllowed = Instant.now().plusSeconds(300);
        Map<UUID, BatchPlanRow> planned = new LinkedHashMap<>();
        List<ExcelImportRow> chosen = new ArrayList<>();
        int selected = 0;
        int skipped = 0, already = 0, notSelected = 0;
        StringBuilder signature = new StringBuilder();
        signature.append(workspaceId).append('|').append(importId).append('|')
                .append(accountId).append('|').append(cfg).append('|');
        for (Instant taken : reserved) signature.append("busy:").append(taken).append(';');

        for (ExcelImportRow source : sources) {
            Post post = source.getPost();
            signature.append("row:").append(source.getId()).append('|')
                    .append(source.getSourceRowNumber()).append('|')
                    .append(source.getSourcePublishDate()).append('|')
                    .append(source.getProcessingStatus()).append('|');
            if (post != null) {
                signature.append(post.getId()).append('|').append(post.getStatus())
                        .append('|').append(post.getApprovalStatus()).append('|')
                        .append(post.getContent()).append('|');
                media.findByPostId(post.getId()).forEach(m -> signature
                        .append(m.getId()).append(':').append(m.getPublicUrl()).append(':')
                        .append(m.getSourceUrl()).append(';'));
            }
            if (post == null || "PUBLISHED".equals(source.getProcessingStatus())
                    || (source.getProcessingStatus() != null
                    && source.getProcessingStatus().startsWith("SKIPPED_"))) {
                skipped++;
                planned.put(source.getId(), new BatchPlanRow(source.getId(), source.getSourceRowNumber(),
                        post == null ? null : post.getId(), "SKIPPED", null,
                        false, false, "Excel row has already been published or skipped"));
                continue;
            }
            boolean exists = jobs.existsByPostIdAndSocialAccountId(post.getId(), accountId);
            signature.append("jobExists:").append(exists).append(';');
            if (exists) {
                already++;
                planned.put(source.getId(), new BatchPlanRow(source.getId(), source.getSourceRowNumber(),
                        post.getId(), "ALREADY_PLANNED", null, false, false,
                        "Publishing Job already exists for this Post and account"));
                continue;
            }
            if (selected >= cfg.maxRows()) {
                notSelected++;
                planned.put(source.getId(), new BatchPlanRow(source.getId(), source.getSourceRowNumber(),
                        post.getId(), "NOT_SELECTED", null, false, false,
                        "Excluded by maxRows limit"));
                continue;
            }
            selected++;
            String approval = post.getApprovalStatus();
            if (post.getContent() == null || post.getContent().isBlank()
                    || approval == null
                    || !Set.of("NOT_SUBMITTED", "PENDING_APPROVAL", "APPROVED").contains(approval)
                    || post.getStatus() == null
                    || !Set.of("DRAFT", "READY", "SCHEDULED").contains(post.getStatus())
                    || ("THREADS".equals(platform) && post.getContent().length() > 500)) {
                // A missing Excel source date is valid in explicit auto-plan/batch:
                // the caller provides a new startDate/startTime for allocation.
                // Do not weaken approval, content, status, or platform validation.
                planned.put(source.getId(), new BatchPlanRow(source.getId(), source.getSourceRowNumber(),
                        post.getId(), "BLOCKED", null, false, false,
                        "Missing content or ineligible Post/approval state"));
                continue;
            }
            chosen.add(source);
        }

        // Protect original future dates before allocating any replacement slots.
        for (ExcelImportRow source : chosen) {
            // Rows imported without "Ngay dang" have no original date to preserve.
            // They receive a new slot in the allocation loop below.
            if (source.getSourcePublishDate() == null) continue;
            ZonedDateTime original = ZonedDateTime.of(source.getSourcePublishDate(),
                    batch.getPublishTime(), ZONE);
            // Future original dates are never automatically rescheduled.
            // If too close to prepare safely, block for review instead of
            // silently treating a future date as expired.
            if (!original.toInstant().isAfter(Instant.now())) continue;
            Post post = source.getPost();
            if (!original.toInstant().isAfter(earliestAllowed)) {
                planned.put(source.getId(), new BatchPlanRow(source.getId(), source.getSourceRowNumber(),
                        post.getId(), "BLOCKED", original.toOffsetDateTime(), false,
                        false, "Original future date is less than 5 minutes away; cannot safely prepare, leave it unchanged"));
                continue;
            }
            if (isOccupied(original.toInstant(), reserved, cfg.intervalMinutes())) {
                planned.put(source.getId(), new BatchPlanRow(source.getId(), source.getSourceRowNumber(),
                        post.getId(), "BLOCKED", original.toOffsetDateTime(), false,
                        false, "Original future date collides with another scheduled Job"));
                continue;
            }
            reserved.add(original.toInstant());
            planned.put(source.getId(), new BatchPlanRow(source.getId(), source.getSourceRowNumber(),
                    post.getId(), "KEEP_ORIGINAL", original.toOffsetDateTime(),
                    isMediaReady(post), !"APPROVED".equals(post.getApprovalStatus()),
                    "Excel date is still in the future"));
        }

        long slot = 0;
        for (ExcelImportRow source : chosen) {
            if (planned.containsKey(source.getId())) continue;
            OffsetDateTime next = null;
            // Maximum horizon of 365 days prevents unbounded loops.
            long bound = (long) cfg.maxPostsPerDay() * 365;
            while (slot < bound) {
                long today = slot / cfg.maxPostsPerDay();
                int withinDay = (int) (slot % cfg.maxPostsPerDay());
                slot++;
                ZonedDateTime candidate = ZonedDateTime.of(
                        cfg.startDate().plusDays(today),
                        cfg.startTime().plusMinutes((long) withinDay * cfg.intervalMinutes()), ZONE);
                if (candidate.toInstant().isAfter(earliestAllowed)
                        && !isOccupied(candidate.toInstant(), reserved, cfg.intervalMinutes())) {
                    next = candidate.toOffsetDateTime();
                    reserved.add(candidate.toInstant());
                    break;
                }
            }
            Post post = source.getPost();
            if (next == null) {
                planned.put(source.getId(), new BatchPlanRow(source.getId(), source.getSourceRowNumber(),
                        post.getId(), "BLOCKED", null, false, false,
                        "No available automatic slot within 365 days"));
            } else {
                planned.put(source.getId(), new BatchPlanRow(source.getId(), source.getSourceRowNumber(),
                        post.getId(), "RESCHEDULED", next,
                        isMediaReady(post), !"APPROVED".equals(post.getApprovalStatus()),
                        source.getSourcePublishDate() == null
                                ? "No original Excel date; new batch date used for Job"
                                : "Old Excel date kept for audit; new date used for Job"));
            }
        }

        List<BatchPlanRow> resultRows = new ArrayList<>();
        for (ExcelImportRow source : sources) {
            resultRows.add(planned.get(source.getId()));
        }
        int keep = (int) resultRows.stream().filter(r -> "KEEP_ORIGINAL".equals(r.action())).count();
        int replace = (int) resultRows.stream().filter(r -> "RESCHEDULED".equals(r.action())).count();
        int blocked = (int) resultRows.stream().filter(r -> "BLOCKED".equals(r.action())).count();
        for (BatchPlanRow r : resultRows) {
            signature.append("plan:").append(r.sourceRowNumber()).append('|')
                    .append(r.action()).append('|').append(r.scheduledAt())
                    .append('|').append(r.mediaReady()).append(';');
        }
        String token = sha256Batch(signature.toString());
        return new BatchSnapshot(new BatchPlanResult(importId, accountId, true, token,
                selected, keep, replace, already, skipped, blocked, notSelected,
                0, List.copyOf(resultRows), List.of()), accountId);
    }

    private boolean isMediaReady(Post post) {
        // No PostMedia records => text-only post; no upload is required.
        // When media records exist, *all* of them must be supported and uploaded.
        return media.findByPostId(post.getId()).stream().allMatch(m ->
                Set.of("IMAGE", "VIDEO", "REEL").contains(m.getMediaType())
                        && m.getPublicUrl() != null
                        && m.getPublicUrl().startsWith("https://res.cloudinary.com/")
                        && (("IMAGE".equals(m.getMediaType()) && m.getPublicUrl().contains("/image/upload/"))
                        || (!"IMAGE".equals(m.getMediaType()) && m.getPublicUrl().contains("/video/upload/"))));
    }

    private boolean isOccupied(Instant time, List<Instant> occupied, int gapMinutes) {
        Duration minimumGap = Duration.ofMinutes(gapMinutes);
        for (Instant existing : occupied) {
            if (Duration.between(existing, time).abs().compareTo(minimumGap) < 0) {
                return true;
            }
        }
        return false;
    }

    private String sha256Batch(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void ensureFuture(Instant instant) {
        if (!instant.isAfter(Instant.now().plusSeconds(60))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Scheduled time must be at least 1 minute in the future");
        }
    }

    private void validatePostAndMedia(Post post, ExcelImport batch) {
        if (post.getContent() == null || post.getContent().isBlank()) {
            throw new IllegalStateException("Empty post content");
        }
        if ("THREADS".equals(batch.getSocialAccount().getPlatform())
                && post.getContent().length() > 500) {
            throw new IllegalStateException("Threads content exceeds 500 characters");
        }
        // Keep the same rule as batch preview: text-only is allowed,
        // but an attached image must have a valid public URL.
        if (!isMediaReady(post)) {
            throw new IllegalStateException("Media not ready; check Google Drive/Cloudinary");
        }
    }

    private String scheduleRow(UUID workspaceId, UUID rowId, UUID postId,
                               UUID accountId, ZonedDateTime at) {
        return scheduleRow(workspaceId, rowId, postId, accountId, at, 0);
    }

    /**
     * Account-serialized conflict recheck for batch requests, inside the Job transaction.
     */
    private String scheduleRow(UUID workspaceId, UUID rowId, UUID postId,
                               UUID accountId, ZonedDateTime at, int minGapMinutes) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            ExcelImportRow locked = entityManager.find(
                    ExcelImportRow.class, rowId, LockModeType.PESSIMISTIC_WRITE);
            if (locked == null) throw new IllegalStateException("Excel row disappeared");
            if ("PUBLISHED".equals(locked.getProcessingStatus())
                    || (locked.getProcessingStatus() != null
                    && locked.getProcessingStatus().startsWith("SKIPPED_"))) {
                throw new IllegalStateException("Already-published row cannot be scheduled");
            }
            if (jobs.existsByPostIdAndSocialAccountId(postId, accountId)) {
                locked.setProcessingStatus("AUTO_SCHEDULED");
                locked.setReason(null);
                return "ALREADY";
            }
            if (minGapMinutes > 0) {
                // Serialize competing planners for the same account, including
                // separate imported Excel files. Other creator paths should also
                // enforce a database uniqueness constraint per Post + account.
                SocialAccount socialAccount = entityManager.find(
                        SocialAccount.class, accountId, LockModeType.PESSIMISTIC_WRITE);
                if (socialAccount == null || !"CONNECTED".equals(socialAccount.getStatus())
                        || !workspaceId.equals(socialAccount.getWorkspace().getId())) {
                    throw new IllegalStateException("Social Account is no longer eligible");
                }
                List<OffsetDateTime> occupied = entityManager.createQuery("""
                                SELECT j.scheduledAt FROM PublishingJob j
                                WHERE j.socialAccount.id = :accountId
                                  AND j.status IN ('PENDING', 'PROCESSING', 'RETRY')
                                  AND j.scheduledAt IS NOT NULL
                                """, OffsetDateTime.class)
                        .setParameter("accountId", accountId)
                        .getResultList();
                List<Instant> occupiedInstants = new ArrayList<>();
                for (OffsetDateTime due : occupied) occupiedInstants.add(due.toInstant());
                if (isOccupied(at.toInstant(), occupiedInstants, minGapMinutes)) {
                    throw new IllegalStateException(
                            "An active publishing job for this account conflicts with the planned time");
                }
            }
            Post post = locked.getPost();
            if (post == null || !postId.equals(post.getId())) {
                throw new IllegalStateException("Excel row Post changed; preview again");
            }
            String approval = post.getApprovalStatus();
            if ("NOT_SUBMITTED".equals(approval)) {
                approvals.submit(postId);
                // Explicitly perform approval after submission; service must read updated status.
                approvals.approve(postId);
            } else if ("PENDING_APPROVAL".equals(approval)) {
                approvals.approve(postId);
            } else if (!"APPROVED".equals(approval)) {
                throw new IllegalStateException("Post approval state is not eligible: " + approval);
            }

            // Transition the already approved Post in the SAME transaction used to create the Job.
            // Do not call PostService.update() here: the schedule-only transition is performed
            // on the managed Post while its approval and media validation remain enforced.
            Post managedPost = entityManager.find(
                    Post.class, postId, LockModeType.PESSIMISTIC_WRITE);
            if (managedPost == null) {
                throw new IllegalStateException("Post not found");
            }
            if (!"APPROVED".equals(managedPost.getApprovalStatus())) {
                throw new IllegalStateException("Post must be approved before scheduling");
            }
            if (!Set.of("DRAFT", "READY", "SCHEDULED").contains(managedPost.getStatus())) {
                throw new IllegalStateException(
                        "Post cannot be scheduled from status: " + managedPost.getStatus());
            }


            ensureFuture(at.toInstant());

// Sử dụng setter của Entity.
            managedPost.setStatus("SCHEDULED");
            managedPost.setScheduledAt(at.toOffsetDateTime());

// Đồng bộ trạng thái trước khi tạo Publishing Job.
            entityManager.flush();

            log.info(
                    "[EXCEL AUTO] pre-job post={} status={} approval={}",
                    postId,
                    managedPost.getStatus(),
                    managedPost.getApprovalStatus()
            );

            publishingJobs.create(
                    postId,
                    accountId,
                    at.toOffsetDateTime()
            );
            locked.setProcessingStatus("AUTO_SCHEDULED");
            locked.setReason(null);
            return "PLANNED";
        }));
    }

    private static String shortReason(RuntimeException ex) {
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return reason.length() > 900 ? reason.substring(0, 900) : reason;
    }

    private void setRowStatus(UUID rowId, String state, String reason) {
        transactions.executeWithoutResult(status -> {
            ExcelImportRow row = entityManager.find(
                    ExcelImportRow.class, rowId, LockModeType.PESSIMISTIC_WRITE);
            if (row != null && !"PUBLISHED".equals(row.getProcessingStatus())
                    && (row.getProcessingStatus() == null
                    || !row.getProcessingStatus().startsWith("SKIPPED_"))) {
                row.setProcessingStatus(state);
                row.setReason(reason);
            }
        });
    }
}
