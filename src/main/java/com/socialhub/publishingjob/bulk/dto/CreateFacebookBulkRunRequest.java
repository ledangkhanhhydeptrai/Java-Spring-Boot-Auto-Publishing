
package com.socialhub.publishingjob.bulk.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateFacebookBulkRunRequest(
        @NotNull UUID workspaceId,
        @NotEmpty List<@NotNull UUID> jobIds
) {
}
