
package com.socialhub.publishingjob.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;

import java.util.UUID;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class PublishingJobDto {

    private PublishingJobDto() {
    }

    public record Create(
            @NotNull UUID socialAccountId,
            OffsetDateTime scheduledAt
    ) {
    }

    public record ConfirmPublished(
            @NotBlank String externalPostId,
            String publishedUrl
    ) {
    }

    public record View(
            UUID id,
            UUID postId,
            UUID socialAccountId,
            String status,
            OffsetDateTime scheduledAt,
            OffsetDateTime publishedAt,
            String publishedUrl,
            @JsonSerialize(using = ViDisplayTextSerializer.class) String lastError,
            Integer attemptCount
    ) {
    }
}
