package com.socialhub.grouppublishingtask.automation;

import java.util.List;
import java.util.UUID;

/**
 * Future integration contract ONLY. No implementation is registered by this patch.
 * A provider must have documented permission to publish to the exact Group.
 * Before wiring dispatch, implement atomic claim, durable result reconciliation,
 * verified group permalink and explicit handling of uncertain outcomes.
 */
public interface GroupPublishingAdapter {
    record Media(String type, String url) { }
    record Publication(UUID workspaceId, UUID groupId, UUID taskId,
                       String externalGroupId, String content, List<Media> media) {
        public Publication { media = List.copyOf(media); }
    }
    enum Outcome { PUBLISHED, FAILED, NEEDS_REVIEW }
    record Result(Outcome outcome, String permalink, String note) { }

    /** Must never return PUBLISHED unless the provider confirms remote publication. */
    Result publish(Publication publication);
}
