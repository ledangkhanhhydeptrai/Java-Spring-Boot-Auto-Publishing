
package com.socialhub.publishingjob.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.facebookoauth.repository.FacebookPageConnectionRepository;
import com.socialhub.post.entity.Post;
import com.socialhub.post.repository.PostRepository;
import com.socialhub.publishingjob.entity.PublishingJob;
import com.socialhub.publishingjob.repository.PublishingJobRepository;
import com.socialhub.publishingreview.entity.PublishingReview;
import com.socialhub.publishingreview.repository.PublishingReviewRepository;
import com.socialhub.security.AccessService;
import com.socialhub.socialaccount.entity.SocialAccount;
import com.socialhub.socialaccount.repository.SocialAccountRepository;
import com.socialhub.workspace.entity.Workspace;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishingRetryAuthorizationTest {

    private PublishingJobRepository jobs;
    private PublishingReviewRepository reviews;
    private PublishingJobServiceImpl service;

    private PublishingJob job;
    private UUID jobId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {

        jobs = mock(PublishingJobRepository.class);
        reviews = mock(PublishingReviewRepository.class);

        PostRepository posts = mock(PostRepository.class);
        SocialAccountRepository accounts =
                mock(SocialAccountRepository.class);
        AccessService access = mock(AccessService.class);
        EntityManager entityManager = mock(EntityManager.class);

        FacebookPageConnectionRepository connections =
                mock(FacebookPageConnectionRepository.class);

        service = new PublishingJobServiceImpl(
                jobs,
                posts,
                accounts,
                access,
                reviews,
                entityManager,
                connections
        );

        jobId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();

        Workspace space = mock(Workspace.class);
        when(space.getId()).thenReturn(workspaceId);

        Post post = mock(Post.class);
        when(post.getWorkspace()).thenReturn(space);
        when(post.getApprovalStatus()).thenReturn("APPROVED");
        when(post.getStatus()).thenReturn("READY");
        when(post.getId()).thenReturn(UUID.randomUUID());

        SocialAccount account = mock(SocialAccount.class);
        when(account.getPlatform()).thenReturn("THREADS");
        when(account.getStatus()).thenReturn("CONNECTED");
        when(account.getWorkspace()).thenReturn(space);
        when(account.getId()).thenReturn(UUID.randomUUID());

        job = new PublishingJob();
        job.setId(jobId);
        job.setPost(post);
        job.setSocialAccount(account);
        job.setStatus("NEEDS_REVIEW");
        job.setAttemptCount(1);

        when(jobs.findByIdForUpdate(jobId))
                .thenReturn(Optional.of(job));
    }

    private PublishingReview verifiedReview() {

        PublishingReview review = mock(PublishingReview.class);

        when(review.getDecision())
                .thenReturn("CONFIRM_NOT_PUBLISHED");

        when(review.getEvidence())
                .thenReturn("Verified absence on Meta account");

        when(review.getNote())
                .thenReturn("Checked target page and API history");

        return review;
    }

    @Test
    void reviewedRetryPreservesAttemptHistory() {

        PublishingReview verified = verifiedReview();

        when(reviews.findByPublishingJob_IdAndAttemptNumber(jobId, 1))
                .thenReturn(Optional.of(verified));

        when(jobs.save(job)).thenReturn(job);

        service.retry(jobId);

        assertEquals("PENDING", job.getStatus());
        assertEquals(1, job.getAttemptCount());
        assertNotNull(job.getScheduledAt());

        verify(jobs, times(1)).save(job);
    }

    @Test
    void missingReviewCannotRetry() {

        assertThrows(
                ApiException.class,
                () -> service.retry(jobId)
        );

        assertEquals("NEEDS_REVIEW", job.getStatus());

        verify(jobs, never()).save(any());
    }

    @Test
    void exhaustedAttemptsCannotRetry() {

        job.setAttemptCount(3);

        assertThrows(
                ApiException.class,
                () -> service.retry(jobId)
        );

        verifyNoInteractions(reviews);
        verify(jobs, never()).save(any());
    }

    @Test
    void secondRetryWithoutNewAttemptIsRejected() {

        PublishingReview verified = verifiedReview();

        when(reviews.findByPublishingJob_IdAndAttemptNumber(jobId, 1))
                .thenReturn(Optional.of(verified));

        when(jobs.save(job)).thenReturn(job);

        service.retry(jobId);

        assertThrows(
                ApiException.class,
                () -> service.retry(jobId)
        );

        verify(jobs, times(1)).save(job);
    }
}
