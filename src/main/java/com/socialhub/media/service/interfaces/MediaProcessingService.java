
package com.socialhub.media.service.interfaces;

import java.util.List;
import java.util.UUID;

public interface MediaProcessingService {

    record MediaResult(
            UUID mediaId,
            String sourceUrl,
            String publicUrl,
            String status,
            String message
    ) {}

    record ProcessResult(
            UUID postId,
            int total,
            int uploaded,
            int skipped,
            int failed,
            List<MediaResult> media
    ) {}

    ProcessResult processPostMedia(UUID postId);
}
