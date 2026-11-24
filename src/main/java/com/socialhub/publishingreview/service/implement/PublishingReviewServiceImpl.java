package com.socialhub.publishingreview.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.publishingjob.entity.PublishingJob;
import com.socialhub.publishingjob.repository.PublishingJobRepository;
import com.socialhub.publishingreview.dto.PublishingReviewDto;
import com.socialhub.publishingreview.entity.PublishingReview;
import com.socialhub.publishingreview.repository.PublishingReviewRepository;
import com.socialhub.publishingreview.service.interfaces.PublishingReviewService;
import com.socialhub.security.AccessService;
import com.socialhub.user.entity.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PublishingReviewServiceImpl implements PublishingReviewService {

    private final PublishingReviewRepository reviews;
    private final PublishingJobRepository jobs;
    private final AccessService access;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    private PublishingReviewDto.View view(PublishingReview review) {
        return new PublishingReviewDto.View(review.getId(), review.getPublishingJob().getId(), review.getReviewedBy().getId(), review.getAttemptNumber(), review.getDecision(), review.getEvidence(), review.getNote(), review.getExternalPostId(), review.getPublishedUrl(), review.getCreatedAt());
    }


    @Override
    @Transactional
    public PublishingReviewDto.View confirmPublished(
            UUID jobId,
            PublishingReviewDto.ConfirmPublished request
    ) {
        PublishingJob job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Publishing job not found"
                ));

        UUID workspaceId = job.getPost().getWorkspace().getId();

        access.admin(workspaceId);

        if (!"NEEDS_REVIEW".equals(job.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Only NEEDS_REVIEW jobs can be confirmed"
            );
        }

        if (request == null
                || request.externalPostId() == null
                || request.externalPostId().isBlank()
                || request.evidence() == null
                || request.evidence().isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "External post ID and evidence are required"
            );
        }

        String externalPostId = request.externalPostId().trim();
        String evidence = request.evidence().trim();

        if (externalPostId.length() > 255) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "External post ID exceeds 255 characters"
            );
        }

        int attemptNumber = job.getAttemptCount();

        if (attemptNumber <= 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Job has no publishing attempt to review"
            );
        }

        if (reviews.findByPublishingJob_IdAndAttemptNumber(
                jobId,
                attemptNumber
        ).isPresent()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Current attempt already has a manual review decision"
            );
        }

        UUID reviewerId = access.userId();

        User reviewer = entityManager.getReference(
                User.class,
                reviewerId
        );

        job.setStatus("PUBLISHED");
        job.setExternalPostId(externalPostId);
        job.setPublishedUrl(request.publishedUrl());
        job.setPublishedAt(OffsetDateTime.now());

        job.setNextRetryAt(null);
        job.setLockedUntil(null);
        job.setLastError(null);

        jobs.save(job);

        // Keep Excel import rows in sync with a manually confirmed publication.
        // The Job lock and this update share the same transaction.
        // A Post created directly in the UI will not match any import row.
        jdbc.update("""
                        UPDATE excel_import_rows e
                        SET processing_status = 'PUBLISHED',
                            published_at = ?,
                            published_url = ?
                        WHERE e.post_id = ?
                          AND e.processing_status = 'IMPORTED'
                        """,
                job.getPublishedAt(),
                job.getPublishedUrl(),
                job.getPost().getId());

        PublishingReview review = new PublishingReview();

        review.setPublishingJob(job);
        review.setReviewedBy(reviewer);
        review.setAttemptNumber(attemptNumber);
        review.setDecision("CONFIRM_PUBLISHED");
        review.setEvidence(evidence);
        review.setNote(request.note());
        review.setExternalPostId(externalPostId);
        review.setPublishedUrl(request.publishedUrl());

        PublishingReview saved = reviews.saveAndFlush(review);

        entityManager.refresh(saved);

        return view(saved);
    }


    @Override
    @Transactional(readOnly = true)
    public List<PublishingReviewDto.View> history(UUID jobId) {

        PublishingJob job = jobs.findById(jobId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Publishing job not found"));

        access.workspace(job.getPost().getWorkspace().getId());

        return reviews.findByPublishingJob_IdOrderByCreatedAtDesc(jobId).stream().map(this::view).toList();
    }


    @Override
    @Transactional
    public PublishingReviewDto.View confirmNotPublished(
            UUID jobId,
            PublishingReviewDto.ConfirmNotPublished request
    ) {
        PublishingJob job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Publishing job not found"
                ));

        UUID workspaceId = job.getPost().getWorkspace().getId();

        access.admin(workspaceId);

        if (!"NEEDS_REVIEW".equals(job.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Only NEEDS_REVIEW jobs can be verified"
            );
        }

        if (request == null
                || request.evidence() == null
                || request.evidence().isBlank()
                || request.note() == null
                || request.note().isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Evidence and verification note are required"
            );
        }

        int attemptNumber = job.getAttemptCount();

        if (attemptNumber <= 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Job has no publishing attempt to review"
            );
        }

        // Prevent unsafe negative confirmation for asynchronous Meta videos.
        // A video/reel may already be published even if permalink is unavailable.
        Boolean hasVideoMedia = jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM post_media pm
                            WHERE pm.post_id = ?
                              AND pm.media_type IN ('VIDEO', 'REEL')
                        )
                        """,
                Boolean.class,
                job.getPost().getId()
        );

        if (Boolean.TRUE.equals(hasVideoMedia)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Cannot confirm VIDEO/REEL as NOT_PUBLISHED after "
                            + "a publishing attempt. Meta may have accepted "
                            + "the upload. Verify the Meta video status "
                            + "before any retry. Automatic retry is blocked."
            );
        }

        if (reviews.findByPublishingJob_IdAndAttemptNumber(
                jobId,
                attemptNumber
        ).isPresent()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Current attempt already has a manual review decision"
            );
        }

        if (job.getExternalPostId() != null
                || job.getPublishedAt() != null
                || job.getPublishedUrl() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Job already contains publication information"
            );
        }

        UUID reviewerId = access.userId();

        User reviewer = entityManager.getReference(
                User.class,
                reviewerId
        );

        PublishingReview review = new PublishingReview();

        review.setPublishingJob(job);
        review.setReviewedBy(reviewer);
        review.setAttemptNumber(attemptNumber);
        review.setDecision("CONFIRM_NOT_PUBLISHED");
        review.setEvidence(request.evidence().trim());
        review.setNote(request.note().trim());

        review.setExternalPostId(null);
        review.setPublishedUrl(null);

        PublishingReview saved = reviews.saveAndFlush(review);

        entityManager.refresh(saved);

        return view(saved);
    }

}
