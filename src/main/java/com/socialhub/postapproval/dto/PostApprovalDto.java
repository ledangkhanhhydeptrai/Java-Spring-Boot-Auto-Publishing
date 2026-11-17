
package com.socialhub.postapproval.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class PostApprovalDto {

    private PostApprovalDto() {
    }

    public record Reject(
            @NotBlank(message = "Reject reason is required")
            String reason
    ) {
    }

    public record View(
            UUID id,
            UUID postId,
            UUID reviewedBy,
            String decision,
            String note,
            OffsetDateTime createdAt
    ) {
    }

    public record Status(
            UUID postId,
            String postStatus,
            String approvalStatus,
            UUID approvedBy,
            OffsetDateTime approvedAt
    ) {
    }
}
