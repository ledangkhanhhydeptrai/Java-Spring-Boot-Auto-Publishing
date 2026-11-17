
package com.socialhub.publishingjob.bulk.dto;

import java.util.UUID;

public record FacebookBulkRunResponse(
        UUID bulkRunId,
        UUID workspaceId,
        String status,
        int totalJobs
) {
}
