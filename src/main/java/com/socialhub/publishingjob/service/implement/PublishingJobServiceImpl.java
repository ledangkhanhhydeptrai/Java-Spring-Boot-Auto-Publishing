
package com.socialhub.publishingjob.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import com.socialhub.post.entity.Post;
import com.socialhub.post.repository.PostRepository;
import com.socialhub.socialaccount.entity.SocialAccount;
import com.socialhub.socialaccount.repository.SocialAccountRepository;
import com.socialhub.publishingjob.entity.PublishingJob;
import com.socialhub.publishingjob.repository.PublishingJobRepository;
import com.socialhub.publishingjob.dto.PublishingJobDto;
import com.socialhub.publishingjob.service.interfaces.PublishingJobService;
import com.socialhub.publishingreview.entity.PublishingReview;
import com.socialhub.publishingreview.repository.PublishingReviewRepository;
import com.socialhub.facebookoauth.repository.FacebookPageConnectionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublishingJobServiceImpl implements PublishingJobService {

    private final PublishingJobRepository jobs;
    private final PostRepository posts;
    private final SocialAccountRepository accounts;
    private final AccessService access;
    private final PublishingReviewRepository reviews;
    private final EntityManager entityManager;
    private final FacebookPageConnectionRepository facebookConnections;

    @Value("${app.facebook.connection-auto-bind:false}")
    private boolean autoBindFacebookConnection;

    private PublishingJobDto.View view(PublishingJob j) {
        return new PublishingJobDto.View(
                j.getId(),
                j.getPost().getId(),
                j.getSocialAccount().getId(),
                j.getStatus(),
                j.getScheduledAt(),
                j.getPublishedAt(),
                j.getPublishedUrl(),
                j.getLastError(),
                j.getAttemptCount()
        );
    }

    @Override
    @Transactional
    public PublishingJobDto.View create(
            UUID postId,
            UUID accountId,
            OffsetDateTime scheduledAt
    ) {

        // Flush pending changes from the current transaction.
        entityManager.flush();

        // Fetch the Post with a pessimistic lock.
        // Do not use refresh(), which can overwrite
        // changes in the current persistence context.
        Post p = entityManager.find(
                Post.class,
                postId,
                LockModeType.PESSIMISTIC_WRITE
        );

        if (p == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "Post not found"
            );
        }

        access.admin(p.getWorkspace().getId());

        log.info(
                "[JOB CREATE] postId={} status={} approval={}",
                postId,
                p.getStatus(),
                p.getApprovalStatus()
        );

        SocialAccount a = accounts.findById(accountId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Account not found"
                ));

        if (!p.getWorkspace().getId()
                .equals(a.getWorkspace().getId())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Different workspaces"
            );
        }

        if (!Set.of("FACEBOOK_PAGE", "THREADS")
                .contains(a.getPlatform())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Only Facebook Page and Threads jobs supported"
            );
        }

        if (!"READY".equals(p.getStatus())
                && !"SCHEDULED".equals(p.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Post must be READY or SCHEDULED"
            );
        }

        if (!"APPROVED".equals(p.getApprovalStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Post must be approved before creating a publishing job"
            );
        }

        OffsetDateTime effectiveScheduledAt = scheduledAt;

        if (effectiveScheduledAt == null) {
            effectiveScheduledAt = p.getScheduledAt();
        }

        if ("SCHEDULED".equals(p.getStatus())
                && effectiveScheduledAt == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Scheduled post requires scheduledAt"
            );
        }

        if ("SCHEDULED".equals(p.getStatus())
                && !effectiveScheduledAt.isAfter(OffsetDateTime.now())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Scheduled time must be in the future"
            );
        }

        if (effectiveScheduledAt == null) {
            effectiveScheduledAt = OffsetDateTime.now();
        }

        if (jobs.existsByPostIdAndSocialAccountId(
                postId,
                accountId
        )) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Publishing job already exists for this post and account"
            );
        }

        PublishingJob j = new PublishingJob();

        j.setPost(p);
        j.setSocialAccount(a);

        if ("FACEBOOK_PAGE".equals(a.getPlatform())
                && autoBindFacebookConnection) {

            var eligible = facebookConnections.findEligible(a.getId());

            if (eligible.isEmpty()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "No eligible Facebook Page connection for this account"
                );
            }

            // Prefer the most recently connected authorized grantor.
            j.setFacebookConnection(eligible.getFirst());
        }

        j.setStatus("PENDING");
        j.setScheduledAt(effectiveScheduledAt);
        j.setPublishedAt(null);

        PublishingJob saved = jobs.saveAndFlush(j);

        log.info(
                "[JOB CREATE] SUCCESS jobId={} postId={} accountId={} scheduledAt={}",
                saved.getId(),
                postId,
                accountId,
                effectiveScheduledAt
        );

        return view(saved);
    }

    @Transactional(readOnly = true)
    public Page<PublishingJobDto.View> listWorkspaceJobs(
            UUID workspaceId,
            int page,
            int size,
            String status,
            UUID postId
    ) {
        access.workspace(workspaceId);

        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid pagination (size 1-100)"
            );
        }

        String normalized = status == null
                ? ""
                : status.trim().toUpperCase(java.util.Locale.ROOT);

        if (!normalized.isEmpty()
                && !Set.of(
                "PENDING",
                "PROCESSING",
                "PUBLISHED",
                "FAILED",
                "NEEDS_REVIEW",
                "CANCELLED",
                "RETRY"
        ).contains(normalized)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid job status"
            );
        }

        if (postId != null) {
            Post p = posts.findById(postId)
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND,
                            "Post not found"
                    ));

            if (!workspaceId.equals(p.getWorkspace().getId())) {
                throw new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Post not found in workspace"
                );
            }
        }

        return jobs.searchWorkspaceJobs(
                workspaceId,
                normalized,
                postId,
                PageRequest.of(
                        page,
                        size,
                        Sort.by(Sort.Direction.DESC, "id")
                )
        ).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PublishingJobDto.View> list(UUID postId) {

        Post p = posts.findById(postId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Post not found"
                ));

        access.workspace(p.getWorkspace().getId());

        return jobs.findByPostId(postId)
                .stream()
                .map(this::view)
                .toList();
    }

    @Override
    @Transactional
    public PublishingJobDto.View cancel(UUID jobId) {

        PublishingJob j = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Job not found"
                ));

        access.admin(j.getPost().getWorkspace().getId());

        if (!"PENDING".equals(j.getStatus())
                && !"FAILED".equals(j.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Job cannot be cancelled"
            );
        }

        j.setStatus("CANCELLED");
        j.setLockedUntil(null);
        j.setNextRetryAt(null);

        return view(jobs.save(j));
    }

    @Override
    @Transactional
    public PublishingJobDto.View confirmPublished(
            UUID jobId,
            String externalPostId,
            String publishedUrl
    ) {

        PublishingJob j = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Job not found"
                ));

        access.admin(j.getPost().getWorkspace().getId());

        throw new ApiException(
                HttpStatus.CONFLICT,
                "Direct publication confirmation is disabled. "
                        + "Use Publishing Review confirmation "
                        + "with evidence instead."
        );
    }

    /**
     * Authorize one reviewed retry for a failed publishing attempt.
     */
    @Override
    @Transactional
    public PublishingJobDto.View retry(UUID jobId) {

        PublishingJob j = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Job not found"
                ));

        access.admin(j.getPost().getWorkspace().getId());

        if (!"NEEDS_REVIEW".equals(j.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Only NEEDS_REVIEW jobs can be authorized for retry"
            );
        }

        int currentAttempt = j.getAttemptCount() == null
                ? 0
                : j.getAttemptCount();

        if (currentAttempt < 1 || currentAttempt >= 3) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Invalid attempt number or maximum attempts reached"
            );
        }

        Post post = j.getPost();

        if (!"APPROVED".equals(post.getApprovalStatus())
                || (!"READY".equals(post.getStatus())
                && !"SCHEDULED".equals(post.getStatus()))) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Post is not approved and ready for publication"
            );
        }

        SocialAccount account = j.getSocialAccount();

        if (account == null
                || !"CONNECTED".equals(account.getStatus())
                || !Set.of(
                "FACEBOOK_PAGE",
                "THREADS"
        ).contains(account.getPlatform())
                || !post.getWorkspace().getId()
                .equals(account.getWorkspace().getId())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Publishing account is not eligible"
            );
        }

        if (j.getExternalPostId() != null
                || j.getPublishedAt() != null
                || j.getPublishedUrl() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Publishing result already exists; retry is not permitted"
            );
        }

        // Meta video/reel publishing is asynchronous. After any attempt,
        // a "not published" observation is not proof the remote request
        // will never complete. Do not re-send the same video job.
        if ("FACEBOOK_PAGE".equals(account.getPlatform()) && currentAttempt > 0) {
            Number videoMediaCount = (Number) entityManager.createNativeQuery("""
                            SELECT COUNT(*) FROM post_media
                            WHERE post_id = :postId
                              AND media_type IN ('REEL', 'VIDEO')
                            """)
                    .setParameter("postId", post.getId())
                    .getSingleResult();
            if (videoMediaCount.longValue() > 0) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "Facebook REEL/VIDEO retry is disabled after an attempt; reconcile Meta publication first"
                );
            }
        }

        // Bulk publishing has a separate retry workflow.
        if ("FACEBOOK_PAGE".equals(account.getPlatform())) {

            @SuppressWarnings("unchecked")
            List<Object> bulkMembership = entityManager.createNativeQuery("""
                            SELECT 1
                            FROM facebook_bulk_run_jobs
                            WHERE publishing_job_id = :jobId
                            LIMIT 1
                            """)
                    .setParameter("jobId", jobId)
                    .getResultList();

            if (!bulkMembership.isEmpty()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "Facebook Bulk retry is disabled; review the bulk run separately"
                );
            }
        }

        PublishingReview review = reviews
                .findByPublishingJob_IdAndAttemptNumber(
                        jobId,
                        currentAttempt
                )
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "Current attempt has not been manually reviewed"
                ));

        if (!"CONFIRM_NOT_PUBLISHED".equals(review.getDecision())
                || review.getEvidence() == null
                || review.getEvidence().isBlank()
                || review.getNote() == null
                || review.getNote().isBlank()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Retry requires CONFIRM_NOT_PUBLISHED with evidence and note"
            );
        }

        j.setStatus("PENDING");
        j.setScheduledAt(OffsetDateTime.now());
        j.setNextRetryAt(null);
        j.setLockedUntil(null);
        j.setProcessingStartedAt(null);
        j.setLastError(null);

        // Preserve attemptCount for the worker's next claim.
        return view(jobs.save(j));
    }

    @Override
    @Transactional
    public PublishingJobDto.View reschedulePending(
            UUID jobId,
            OffsetDateTime scheduledAt
    ) {
        if (scheduledAt == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "scheduledAt is required"
            );
        }

        OffsetDateTime now = OffsetDateTime.now();

        // Đủ thời gian cho Bridge chuẩn bị Bulk Run.
        if (!scheduledAt.isAfter(now.plusMinutes(5))
                || scheduledAt.isAfter(now.plusDays(30))) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Scheduled time must be more than 20 minutes ahead and within 30 days"
            );
        }

        PublishingJob job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Publishing Job not found"
                ));

        UUID workspaceId = job.getPost().getWorkspace().getId();
        access.admin(workspaceId);

        if (!"PENDING".equals(job.getStatus())
                || (job.getAttemptCount() != null
                && job.getAttemptCount() != 0)
                || job.getExternalPostId() != null
                || job.getPublishedAt() != null
                || job.getPublishedUrl() != null
                || job.getProcessingStartedAt() != null
                || job.getNextRetryAt() != null
                || job.getLockedUntil() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Job has already been attempted or is not eligible for rescheduling"
            );
        }

        Post post = job.getPost();
        SocialAccount account = job.getSocialAccount();


        if (!"FACEBOOK_PAGE".equals(account.getPlatform())
                || !"CONNECTED".equals(account.getStatus())
                || !workspaceId.equals(account.getWorkspace().getId())
                || !"APPROVED".equals(post.getApprovalStatus())
                || (!"READY".equals(post.getStatus())
                && !"SCHEDULED".equals(post.getStatus()))
                || job.getFacebookConnection() == null
                || !"CONNECTED".equals(job.getFacebookConnection().getStatus())
                || !account.getId().equals(
                job.getFacebookConnection().getSocialAccount().getId())) {

            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Post, account or Facebook connection is not eligible"
            );
        }


        // Không thay đổi Job đã thuộc Bulk Run hay Campaign.
        Number linked = (Number) entityManager.createNativeQuery("""
                        SELECT
                            (SELECT COUNT(*)
                             FROM facebook_bulk_run_jobs
                             WHERE publishing_job_id = :jobId)
                            +
                            (SELECT COUNT(*)
                             FROM campaign_jobs
                             WHERE publishing_job_id = :jobId)
                        """)
                .setParameter("jobId", jobId)
                .getSingleResult();

        if (linked.longValue() != 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Job is already linked to a Bulk Run or Campaign"
            );
        }

        // Ngăn lịch trùng hoặc cách nhau dưới một phút
        // với các Job đang hoạt động cùng tài khoản.
        Number conflicts = (Number) entityManager.createNativeQuery("""
                        SELECT COUNT(*)
                        FROM publishing_jobs other
                        WHERE other.social_account_id = :accountId
                          AND other.id <> :jobId
                          AND other.status IN ('PENDING', 'PROCESSING', 'RETRY')
                          AND other.scheduled_at > :startAt
                          AND other.scheduled_at < :endAt
                        """)
                .setParameter("accountId", account.getId())
                .setParameter("jobId", jobId)
                .setParameter("startAt", scheduledAt.minusMinutes(1))
                .setParameter("endAt", scheduledAt.plusMinutes(1))
                .getSingleResult();

        if (conflicts.longValue() > 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Another active Job is scheduled within one minute"
            );
        }

        job.setScheduledAt(scheduledAt);

        return view(jobs.saveAndFlush(job));
    }

    @Override
    @Transactional
    public List<PublishingJobDto.View> reschedulePendingBatch(
            List<UUID> jobIds,
            OffsetDateTime startAt,
            int intervalMinutes
    ) {
        if (jobIds == null || jobIds.isEmpty()
                || jobIds.size() > 50
                || jobIds.contains(null)
                || new java.util.HashSet<>(jobIds).size() != jobIds.size()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Provide 1..50 unique Job IDs"
            );
        }

        if (startAt == null || intervalMinutes < 1
                || intervalMinutes > 1440) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid startAt or intervalMinutes"
            );
        }

        List<PublishingJobDto.View> results =
                new java.util.ArrayList<>();

        for (int i = 0; i < jobIds.size(); i++) {
            OffsetDateTime scheduledAt =
                    startAt.plusMinutes((long) i * intervalMinutes);

            results.add(reschedulePending(
                    jobIds.get(i),
                    scheduledAt
            ));
        }

        return List.copyOf(results);
    }
}
