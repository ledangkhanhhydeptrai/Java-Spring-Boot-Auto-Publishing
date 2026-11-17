
package com.socialhub.publishingreview.dto;

import jakarta.validation.constraints.NotBlank;


import java.time.OffsetDateTime;
import java.util.UUID;

public final class PublishingReviewDto {

    private PublishingReviewDto() {
    }

    public record ConfirmPublished(
            @NotBlank(message = "External post ID is required")
            String externalPostId,

            String publishedUrl,

            @NotBlank(message = "Evidence is required")
            String evidence,

            String note
    ) {
    }

    public record ConfirmNotPublished(
            @NotBlank(message = "Evidence is required")
            String evidence,

            @NotBlank(message = "Verification note is required")
            String note
    ) {
    }

    public record View(
            UUID id,
            UUID publishingJobId,
            UUID reviewedBy,
            Integer attemptNumber,
            String decision,
            String evidence,
            String note,
            String externalPostId,
            String publishedUrl,
            OffsetDateTime createdAt
    ) {
    }
}
