
package com.socialhub.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class CampaignDto {

    private CampaignDto() {
    }

    public record Create(
            @NotNull(message = "Workspace ID is required")
            UUID workspaceId,

            @NotBlank(message = "Campaign name is required")
            @Size(max = 255)
            String name
    ) {
    }

    public record AddJob(
            @NotNull(message = "Publishing Job ID is required")
            UUID publishingJobId
    ) {
    }

    public record View(
            UUID id,
            UUID workspaceId,
            String name,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record Progress(
            UUID campaignId,
            String campaignName,
            String campaignStatus,
            long total,
            long pending,
            long processing,
            long published,
            long failed,
            long needsReview,
            long cancelled,
            double progressPercent
    ) {
    }
}
