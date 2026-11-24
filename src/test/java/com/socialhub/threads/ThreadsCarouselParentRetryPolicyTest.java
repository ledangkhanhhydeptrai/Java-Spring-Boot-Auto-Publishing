package com.socialhub.threads;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThreadsCarouselParentRetryPolicyTest {
    @Test
    void retriesOnlySpecificCarouselChildPropagationRejection() {
        var matching = new ThreadsPublishingService.ThreadsApiException(
                "/v1.0/me/threads", 400, "100", "4279004");
        assertTrue(ThreadsPublishingService.isCarouselChildPropagationError(matching));
    }

    @Test
    void neverRetriesOtherMetaRejectionsOrUnknownNetworkFailures() {
        assertFalse(ThreadsPublishingService.isCarouselChildPropagationError(
                new ThreadsPublishingService.ThreadsApiException(
                        "/v1.0/me/threads", 400, "100", "4279009")));
        assertFalse(ThreadsPublishingService.isCarouselChildPropagationError(
                new ThreadsPublishingService.ThreadsApiException(
                        "/v1.0/me/threads", 429, "100", "4279004")));
        assertFalse(ThreadsPublishingService.isCarouselChildPropagationError(
                new ThreadsPublishingService.ThreadsApiException(
                        "/v1.0/me/threads", 400, "190", "4279004")));
        assertFalse(ThreadsPublishingService.isCarouselChildPropagationError(
                new IllegalStateException("timeout")));
    }
}
