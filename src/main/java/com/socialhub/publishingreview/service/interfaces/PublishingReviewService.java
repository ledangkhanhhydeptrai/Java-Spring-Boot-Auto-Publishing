
package com.socialhub.publishingreview.service.interfaces;

import com.socialhub.publishingreview.dto.PublishingReviewDto;

import java.util.List;
import java.util.UUID;

public interface PublishingReviewService {

    PublishingReviewDto.View confirmPublished(
            UUID jobId,
            PublishingReviewDto.ConfirmPublished request
    );

    PublishingReviewDto.View confirmNotPublished(
            UUID jobId,
            PublishingReviewDto.ConfirmNotPublished request
    );

    List<PublishingReviewDto.View> history(UUID jobId);
}
