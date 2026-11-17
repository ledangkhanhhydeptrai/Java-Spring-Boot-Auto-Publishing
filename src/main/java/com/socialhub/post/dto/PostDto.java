
package com.socialhub.post.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

public final class PostDto {

    private PostDto() {
    }

    public record Create(
            String topic,
            @NotBlank String content,
            String status,
            OffsetDateTime scheduledAt
    ) {
    }

    public record Update(
            String topic,
            @NotBlank String content,
            String status,
            OffsetDateTime scheduledAt
    ) {
    }

    public record View(
            UUID id,
            String topic,
            String content,
            String status,
            OffsetDateTime scheduledAt
    ) {
    }
}
