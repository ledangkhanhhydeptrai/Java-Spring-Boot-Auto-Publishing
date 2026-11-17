package com.socialhub.publishingjob.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.post.entity.Post;
import com.socialhub.post.repository.PostRepository;
import com.socialhub.publishingjob.entity.PublishingJob;
import com.socialhub.publishingjob.repository.PublishingJobRepository;
import com.socialhub.publishingreview.repository.PublishingReviewRepository;
import com.socialhub.security.AccessService;
import com.socialhub.socialaccount.repository.SocialAccountRepository;
import com.socialhub.workspace.entity.Workspace;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for temporarily disabled high-risk publishing operations.
 * No Spring context, database connection, scheduler, or Meta HTTP calls.
 * These tests require retry() and direct confirmPublished() to be disabled.
 */
class PublishingJobSafetyTest {

    private PublishingJobRepository jobs;
    private PostRepository posts;
    private SocialAccountRepository accounts;
    private AccessService access;
    private PublishingReviewRepository reviews;
    private EntityManager entityManager;
    private PublishingJobServiceImpl service;

    private UUID jobId;
    private UUID workspaceId;
    private PublishingJob job;

    @BeforeEach
    void setUp() {
        jobs = mock(PublishingJobRepository.class);
        posts = mock(PostRepository.class);
        accounts = mock(SocialAccountRepository.class);
        access = mock(AccessService.class);
        reviews = mock(PublishingReviewRepository.class);
        entityManager = mock(EntityManager.class);

        service = new PublishingJobServiceImpl(
                jobs, posts, accounts, access, reviews, entityManager
        );

        jobId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();

        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(workspaceId);
        Post post = mock(Post.class);
        when(post.getWorkspace()).thenReturn(workspace);

        job = new PublishingJob();
        job.setId(jobId);
        job.setPost(post);
        job.setStatus("NEEDS_REVIEW");
        job.setAttemptCount(1);
    }

    @Test
    void retry_existingJob_isBlockedWithoutChangingJob() {
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));

        ApiException error = assertThrows(ApiException.class,
                () -> service.retry(jobId));
        assertNotNull(error);

        verify(access).admin(workspaceId);
        verify(jobs, never()).save(any(PublishingJob.class));
        verifyNoInteractions(reviews, posts, accounts, entityManager);
        assertEquals("NEEDS_REVIEW", job.getStatus());
        assertEquals(1, job.getAttemptCount());
    }

    @Test
    void confirmPublished_existingJob_isBlockedWithoutChangingJob() {
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));

        ApiException error = assertThrows(ApiException.class,
                () -> service.confirmPublished(jobId, "example_external_id", "https://example.com/post"));
        assertNotNull(error);

        verify(access).admin(workspaceId);
        verify(jobs, never()).save(any(PublishingJob.class));
        verifyNoInteractions(reviews, posts, accounts, entityManager);
        assertEquals("NEEDS_REVIEW", job.getStatus());
        assertNull(job.getExternalPostId());
        assertNull(job.getPublishedUrl());
        assertNull(job.getPublishedAt());
    }

    @Test
    void retry_missingJob_isRejectedWithoutChanges() {
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.retry(jobId));
        verify(jobs, never()).save(any(PublishingJob.class));
        verifyNoInteractions(access, reviews, posts, accounts, entityManager);
    }

    @Test
    void confirmPublished_missingJob_isRejectedWithoutChanges() {
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.empty());

        assertThrows(ApiException.class,
                () -> service.confirmPublished(jobId, "post_id", "https://example.com"));
        verify(jobs, never()).save(any(PublishingJob.class));
        verifyNoInteractions(access, reviews, posts, accounts, entityManager);
    }
}
