package com.socialhub.publishingreview.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.post.entity.Post;
import com.socialhub.publishingjob.entity.PublishingJob;
import com.socialhub.publishingjob.repository.PublishingJobRepository;
import com.socialhub.publishingreview.dto.PublishingReviewDto;
import com.socialhub.publishingreview.entity.PublishingReview;
import com.socialhub.publishingreview.repository.PublishingReviewRepository;
import com.socialhub.security.AccessService;
import com.socialhub.user.entity.User;
import com.socialhub.workspace.entity.Workspace;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito tests: no Spring context, PostgreSQL, scheduler, or Meta HTTP.
 * Checks the existing PublishingReviewServiceImpl authorization/validation paths.
 */
class PublishingReviewSafetyTest {

    private PublishingReviewRepository reviews;
    private PublishingJobRepository jobs;
    private AccessService access;
    private EntityManager entityManager;
    private PublishingReviewServiceImpl service;

    private UUID jobId;
    private UUID workspaceId;
    private UUID reviewerId;
    private PublishingJob job;

    @BeforeEach
    void setUp() {
        reviews = mock(PublishingReviewRepository.class);
        jobs = mock(PublishingJobRepository.class);
        access = mock(AccessService.class);
        entityManager = mock(EntityManager.class);
        service = new PublishingReviewServiceImpl(
                reviews,
                jobs,
                access,
                entityManager,
                mock(JdbcTemplate.class)
        );

        jobId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        reviewerId = UUID.randomUUID();

        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(workspaceId);
        Post post = new Post();
        post.setWorkspace(workspace);
        job = new PublishingJob();
        job.setPost(post);
        job.setStatus("NEEDS_REVIEW");
        job.setAttemptCount(1);
    }

    private void stubJob() {
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
    }


    private PublishingReviewDto.ConfirmPublished publishedRequest(
            String externalId, String url, String evidence, String note) {
        PublishingReviewDto.ConfirmPublished request = mock(PublishingReviewDto.ConfirmPublished.class);
        when(request.externalPostId()).thenReturn(externalId);
        when(request.evidence()).thenReturn(evidence);
        when(request.publishedUrl()).thenReturn(url);
        when(request.note()).thenReturn(note);
        return request;
    }

    private PublishingReviewDto.ConfirmNotPublished notPublishedRequest(String evidence, String note) {
        PublishingReviewDto.ConfirmNotPublished request = mock(PublishingReviewDto.ConfirmNotPublished.class);
        when(request.evidence()).thenReturn(evidence);
        when(request.note()).thenReturn(note);
        return request;
    }

    @Test
    void confirmPublished_missingJob_rejectedWithoutWrites() {
        assertThrows(ApiException.class, () -> service.confirmPublished(
                jobId, publishedRequest("12345", "https://example.com/post", "manual evidence", "verified")));
        verifyNoInteractions(reviews, entityManager);
        verify(jobs, never()).save(any());
    }

    @Test
    void confirmPublished_requiresNeedsReviewStatus() {
        job.setStatus("PENDING");
        stubJob();
        assertThrows(ApiException.class, () -> service.confirmPublished(
                jobId, publishedRequest("12345", "https://example.com/post", "manual evidence", "verified")));
        verify(access).admin(workspaceId);
        verifyNoInteractions(reviews, entityManager);
        verify(jobs, never()).save(any());
    }

    @Test
    void confirmPublished_requiresExternalIdAndEvidence() {
        stubJob();
        assertThrows(ApiException.class, () -> service.confirmPublished(
                jobId, publishedRequest(" ", null, "manual evidence", null)));
        assertThrows(ApiException.class, () -> service.confirmPublished(
                jobId, publishedRequest("12345", null, "  ", null)));
        verifyNoInteractions(reviews, entityManager);
        verify(jobs, never()).save(any());
    }

    @Test
    void confirmNotPublished_requiresEvidenceAndNote() {
        stubJob();
        assertThrows(ApiException.class, () -> service.confirmNotPublished(
                jobId, notPublishedRequest(" ", "verified")));
        assertThrows(ApiException.class, () -> service.confirmNotPublished(
                jobId, notPublishedRequest("manual evidence", " ")));
        verifyNoInteractions(reviews, entityManager);
        verify(jobs, never()).save(any());
    }

    @Test
    void confirmNotPublished_rejectsDuplicateDecision() {
        stubJob();
        when(reviews.findByPublishingJob_IdAndAttemptNumber(jobId, 1))
                .thenReturn(Optional.of(new PublishingReview()));
        assertThrows(ApiException.class, () -> service.confirmNotPublished(
                jobId, notPublishedRequest("manual evidence", "confirmed not published")));
        verify(reviews, never()).saveAndFlush(any());
        verify(jobs, never()).save(any());
    }

    @Test
    void confirmPublished_rejectsDuplicateDecision() {
        stubJob();
        when(reviews.findByPublishingJob_IdAndAttemptNumber(jobId, 1))
                .thenReturn(Optional.of(new PublishingReview()));
        assertThrows(ApiException.class, () -> service.confirmPublished(
                jobId, publishedRequest("12345", null, "manual evidence", "verified")));
        verify(reviews, never()).saveAndFlush(any());
        verify(jobs, never()).save(any());
    }

    @Test
    void confirmPublished_updatesJobAndRecordsReview() {
        stubJob();
        when(access.userId()).thenReturn(reviewerId);
        User reviewer = mock(User.class);
        when(reviewer.getId()).thenReturn(reviewerId);
        when(entityManager.getReference(User.class, reviewerId)).thenReturn(reviewer);
        when(reviews.saveAndFlush(any(PublishingReview.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.confirmPublished(jobId,
                publishedRequest("12345", "https://example.com/post", "manual evidence", "verified"));

        assertEquals("PUBLISHED", job.getStatus());
        assertEquals("12345", job.getExternalPostId());
        assertEquals("https://example.com/post", job.getPublishedUrl());
        assertNotNull(job.getPublishedAt());
        assertEquals("CONFIRM_PUBLISHED", result.decision());
        assertEquals("manual evidence", result.evidence());
        verify(jobs).save(eq(job));
        verify(reviews).saveAndFlush(any(PublishingReview.class));
    }

    @Test
    void confirmNotPublished_recordsReviewWithoutRequeuingJob() {
        stubJob();
        when(access.userId()).thenReturn(reviewerId);
        User reviewer = mock(User.class);
        when(reviewer.getId()).thenReturn(reviewerId);
        when(entityManager.getReference(User.class, reviewerId)).thenReturn(reviewer);
        when(reviews.saveAndFlush(any(PublishingReview.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.confirmNotPublished(jobId,
                notPublishedRequest("manual evidence", "confirmed not published"));

        assertEquals("NEEDS_REVIEW", job.getStatus());
        assertEquals("CONFIRM_NOT_PUBLISHED", result.decision());
        assertEquals("manual evidence", result.evidence());
        assertEquals("confirmed not published", result.note());
        verify(jobs, never()).save(any());
        verify(reviews).saveAndFlush(any(PublishingReview.class));
    }
}
