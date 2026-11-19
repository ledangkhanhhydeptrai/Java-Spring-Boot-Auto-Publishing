
package com.socialhub.publishingjob.bulk.controller;

import com.socialhub.publishingjob.bulk.dto.CreateFacebookBulkRunRequest;
import com.socialhub.publishingjob.bulk.dto.FacebookBulkRunResponse;
import com.socialhub.publishingjob.bulk.service.FacebookBulkRunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/facebook/bulk-runs")
@RequiredArgsConstructor
public class FacebookBulkRunController {

    private final FacebookBulkRunService service;

    public record RescheduleRequest(@NotNull OffsetDateTime scheduledAt) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FacebookBulkRunResponse create(
            Authentication authentication,
            @Valid @RequestBody CreateFacebookBulkRunRequest request
    ) {
        return service.create(userId(authentication), request);
    }

    @GetMapping("/{runId}")
    public FacebookBulkRunResponse get(
            Authentication authentication,
            @PathVariable UUID runId
    ) {
        return service.get(userId(authentication), runId);
    }

    /**
     * Read-only diagnostics of feature flags; never starts a run.
     */
    @GetMapping("/{runId}/live-readiness")
    public FacebookBulkRunService.LiveReadiness liveReadiness(
            Authentication authentication,
            @PathVariable UUID runId
    ) {
        return service.liveReadiness(userId(authentication), runId);
    }

    /**
     * Change times of existing untouched DRAFT jobs; no duplicate jobs are created.
     */
    @PatchMapping("/{runId}/reschedule")
    public FacebookBulkRunService.RescheduleResult reschedule(
            Authentication authentication,
            @PathVariable UUID runId,
            @Valid @RequestBody RescheduleRequest request
    ) {
        return service.reschedule(userId(authentication), runId, request.scheduledAt());
    }

    @PostMapping("/{runId}/start")
    public FacebookBulkRunResponse start(
            Authentication authentication,
            @PathVariable UUID runId
    ) {
        return service.start(userId(authentication), runId);
    }

    @PostMapping("/{runId}/cancel")
    public FacebookBulkRunResponse cancel(
            Authentication authentication,
            @PathVariable UUID runId
    ) {
        return service.cancel(userId(authentication), runId);
    }

    private UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
