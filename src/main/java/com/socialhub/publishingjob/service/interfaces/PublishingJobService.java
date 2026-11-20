
package com.socialhub.publishingjob.service.interfaces;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.socialhub.publishingjob.dto.PublishingJobDto;
import org.springframework.data.domain.Page;

public interface PublishingJobService {

    PublishingJobDto.View create(
            UUID postId,
            UUID accountId,
            OffsetDateTime scheduledAt
    );

    Page<PublishingJobDto.View> listWorkspaceJobs(UUID workspaceId, int page, int size,
                                                  String status, UUID postId);

    PublishingJobDto.View reschedulePending(
            UUID jobId,
            OffsetDateTime scheduledAt
    );

    List<PublishingJobDto.View> reschedulePendingBatch(
            List<UUID> jobIds,
            OffsetDateTime startAt,
            int intervalMinutes
    );

    List<PublishingJobDto.View> list(UUID postId);

    PublishingJobDto.View cancel(UUID jobId);

    PublishingJobDto.View confirmPublished(
            UUID jobId,
            String externalPostId,
            String publishedUrl
    );

    PublishingJobDto.View retry(UUID jobId);
}
