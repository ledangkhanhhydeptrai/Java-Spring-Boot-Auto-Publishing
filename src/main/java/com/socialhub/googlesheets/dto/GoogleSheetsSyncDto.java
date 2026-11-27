package com.socialhub.googlesheets.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public final class GoogleSheetsSyncDto {
    private GoogleSheetsSyncDto() {
    }

    public record ConnectRequest(@NotBlank String spreadsheetUrl, String tabName, Boolean autoSync) {
    }

    public record Connection(UUID id, UUID workspaceId, String spreadsheetId, String tabGid,
                             String tabName, boolean autoSync, String syncStatus,
                             String lastSyncedAt,
                             @JsonSerialize(using = ViDisplayTextSerializer.class) String lastError) {
    }

    public record SyncResult(UUID connectionId, int readRows, int inserted, int updated,
                             int unchanged, int notPublishable, int missing, String status) {
    }

    public record StagedRow(int sourceRowNumber, String content, String topic, String image1,
                            String image2, String sourcePublishDate, String sourceStatus,
                            String publishedUrl, boolean publishEligible, boolean presentInSheet) {
    }
}
