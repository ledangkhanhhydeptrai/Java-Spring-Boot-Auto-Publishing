package com.socialhub.threads;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Manual publishing endpoint.
 */
@RestController
@RequestMapping("/api/threads/publishing")
@RequiredArgsConstructor
public class ThreadsPublishingController {
    private final ThreadsPublishingService service;

    public record Result(UUID jobId, String status, String externalPostId, String publishedUrl,
                         @JsonSerialize(using = ViDisplayTextSerializer.class) String message) {
    }

    @PostMapping("/jobs/{jobId}/publish-now")
    public Result publishNow(@PathVariable UUID jobId) {
        return service.publishNow(jobId);
    }
}
