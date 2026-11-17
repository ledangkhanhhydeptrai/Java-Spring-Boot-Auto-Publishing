package com.socialhub.threads;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Manual publishing endpoint. */
@RestController
@RequestMapping("/api/threads/publishing")
@RequiredArgsConstructor
public class ThreadsPublishingController {
    private final ThreadsPublishingService service;
    public record Result(UUID jobId, String status, String externalPostId, String publishedUrl, String message) {}

    @PostMapping("/jobs/{jobId}/publish-now")
    public Result publishNow(@PathVariable UUID jobId) { return service.publishNow(jobId); }
}
