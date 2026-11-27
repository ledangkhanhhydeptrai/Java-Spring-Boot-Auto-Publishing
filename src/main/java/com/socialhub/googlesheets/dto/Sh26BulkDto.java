package com.socialhub.googlesheets.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two-step SH26 import. Preview is read-only. Confirm creates drafts, never publishing jobs.
 */
public final class Sh26BulkDto {
    private Sh26BulkDto() {
    }

    public record Request(@NotBlank String spreadsheetUrl,
                          @NotBlank String tabName,
                          @NotNull Map<String, UUID> targets) {
    }

    public record Confirm(Request request, @NotBlank String previewToken,
                          @NotBlank String confirmation) {
    }

    public record Group(String group, int rows, String topicPageName,
                        String selectedPageName, String selectedPageId, String mediaKinds) {
    }

    public record Preview(int rows, int images, int videos, int reels,
                          int existingSh26Posts, String previewToken,
                          boolean valid, List<Group> groups,
                          @JsonSerialize(contentUsing = ViDisplayTextSerializer.class) List<String> errors,
                          boolean createsJobs) {
    }

    public record ExistingPost(String code, UUID postId, String status, UUID importId, boolean hasJob, String topic) {
    }

    public record Imported(String group, UUID socialAccountId, UUID importId,
                           int createdDrafts, int skipped) {
    }

    public record Result(int importedGroups, int createdDrafts, boolean publishingJobsCreated,
                         List<Imported> imports) {
    }
}
