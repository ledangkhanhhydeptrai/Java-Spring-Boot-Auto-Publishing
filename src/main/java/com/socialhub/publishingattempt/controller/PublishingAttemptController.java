
package com.socialhub.publishingattempt.controller;

import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;

import com.socialhub.publishingattempt.repository.PublishingAttemptRepository;

import com.socialhub.publishingjob.entity.PublishingJob;
import com.socialhub.publishingjob.repository.PublishingJobRepository;

@RestController
@RequestMapping("/api/jobs/{jobId}/attempts")
@RequiredArgsConstructor
public class PublishingAttemptController {

    private final PublishingAttemptRepository attempts;
    private final PublishingJobRepository jobs;
    private final AccessService access;

    public record View(
            UUID id,
            Integer attemptNumber,
            String status,
            String errorMessage,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt
    ) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<View> list(@PathVariable UUID jobId) {

        PublishingJob j = jobs.findById(jobId)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                "Job not found"
                        )
                );

        access.workspace(
                j.getPost().getWorkspace().getId()
        );

        return attempts.findByPublishingJobId(jobId)
                .stream()
                .map(a -> new View(
                        a.id,
                        a.attemptNumber,
                        a.status,
                        a.errorMessage,
                        a.startedAt,
                        a.finishedAt
                ))
                .toList();
    }
}
