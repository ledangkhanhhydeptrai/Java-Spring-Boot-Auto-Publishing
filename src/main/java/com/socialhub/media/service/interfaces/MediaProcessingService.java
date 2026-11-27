
package com.socialhub.media.service.interfaces;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;

import java.util.List;
import java.util.UUID;

public interface MediaProcessingService {

    record MediaResult(
            UUID mediaId,
            String sourceUrl,
            String publicUrl,
            String status,
            @JsonSerialize(using = ViDisplayTextSerializer.class) String message
    ) {
    }

    record ProcessResult(
            UUID postId,
            int total,
            int uploaded,
            int skipped,
            int failed,
            List<MediaResult> media
    ) {
    }

    ProcessResult processPostMedia(UUID postId);
}
