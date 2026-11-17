
package com.socialhub.publishingjob.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.socialhub.publishingjob.dto.PublishingJobDto;
import com.socialhub.publishingjob.service.interfaces.PublishingJobService;
import com.socialhub.publishingreview.dto.PublishingReviewDto;
import com.socialhub.publishingreview.service.interfaces.PublishingReviewService;

@RestController
@RequiredArgsConstructor
public class PublishingJobController {

    private final PublishingJobService publishingJobService;
    private final PublishingReviewService reviewService;

    @GetMapping("/api/workspaces/{workspaceId}/publishing-jobs")
    public Page<PublishingJobDto.View> listWorkspaceJobs(
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(required = false) UUID postId
    ) {
        return publishingJobService.listWorkspaceJobs(
                workspaceId, page, size, status, postId
        );
    }

    @PostMapping("/api/posts/{postId}/jobs")
    public PublishingJobDto.View create(
            @PathVariable UUID postId,
            @Valid @RequestBody PublishingJobDto.Create r
    ) {
        return publishingJobService.create(
                postId,
                r.socialAccountId(),
                r.scheduledAt()
        );
    }

    @GetMapping("/api/posts/{postId}/jobs")
    public List<PublishingJobDto.View> list(
            @PathVariable UUID postId
    ) {
        return publishingJobService.list(postId);
    }

    @PostMapping("/api/jobs/{jobId}/cancel")
    public PublishingJobDto.View cancel(
            @PathVariable UUID jobId
    ) {
        return publishingJobService.cancel(jobId);
    }

    @PostMapping("/api/jobs/{jobId}/confirm-published")
    public PublishingReviewDto.View confirmPublished(
            @PathVariable UUID jobId,
            @Valid @RequestBody PublishingReviewDto.ConfirmPublished request
    ) {
        return reviewService.confirmPublished(jobId, request);
    }

    @PostMapping("/api/jobs/{jobId}/confirm-not-published")
    public PublishingReviewDto.View confirmNotPublished(
            @PathVariable UUID jobId,
            @Valid @RequestBody PublishingReviewDto.ConfirmNotPublished request
    ) {
        return reviewService.confirmNotPublished(jobId, request);
    }

    @PostMapping("/api/jobs/{jobId}/retry")
    public PublishingJobDto.View retry(
            @PathVariable UUID jobId
    ) {
        return publishingJobService.retry(jobId);
    }

    @GetMapping("/api/jobs/{jobId}/reviews")
    public List<PublishingReviewDto.View> getReviews(
            @PathVariable UUID jobId
    ) {
        return reviewService.history(jobId);
    }
}
