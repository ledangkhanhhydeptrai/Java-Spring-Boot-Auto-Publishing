package com.socialhub.googlesheets.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;

import java.util.List;
import java.util.UUID;

/**
 * Read-only reconciliation: no Post, PublishingJob or Facebook mutations.
 */
public final class GoogleSheetsPublishingBridgeDto {
    private GoogleSheetsPublishingBridgeDto() {
    }

    public record Row(
            int sheetRowNumber,
            String category,
            String sourceStatus,
            String topic,
            UUID existingPostId,
            int existingJobs,
            int publishedJobs,
            @JsonSerialize(using = ViDisplayTextSerializer.class) String reason
    ) {
    }

    public record Preview(
            UUID connectionId,
            UUID importId,
            UUID socialAccountId,
            int totalSheetRows,
            int sheetMarkedPublished,
            int sheetUnpublished,
            int alreadyPublishedInBackend,
            int existingJobsNotPublished,
            int matchedWithoutJobs,
            int notImported,
            int requiresReview,
            int notPresentInSheet,
            int offset,
            int limit,
            List<Row> rows,
            boolean readOnly,
            @JsonSerialize(using = ViDisplayTextSerializer.class) String message
    ) {
    }
}
