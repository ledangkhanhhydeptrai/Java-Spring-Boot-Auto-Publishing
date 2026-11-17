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
        boolean ready = media.findByPostId(post.getId()).stream().allMatch(m ->
                "IMAGE".equals(m.getMediaType())
                        && m.getPublicUrl() != null && !m.getPublicUrl().isBlank());
        if (!ready) {
            throw new IllegalStateException("Media not ready; check Google Drive/Cloudinary");
        }
    }

    private String scheduleRow(UUID workspaceId, UUID rowId, UUID postId,
                               UUID accountId, ZonedDateTime at) {
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
            Post post = locked.getPost();
            if (post == null) throw new IllegalStateException("Excel row has no Post");
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

