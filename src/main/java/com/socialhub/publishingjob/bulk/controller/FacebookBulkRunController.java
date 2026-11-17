
package com.socialhub.publishingjob.bulk.controller;

import com.socialhub.publishingjob.bulk.dto.CreateFacebookBulkRunRequest;
import com.socialhub.publishingjob.bulk.dto.FacebookBulkRunResponse;
import com.socialhub.publishingjob.bulk.service.FacebookBulkRunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/facebook/bulk-runs")
@RequiredArgsConstructor
public class FacebookBulkRunController {

    private final FacebookBulkRunService service;

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
