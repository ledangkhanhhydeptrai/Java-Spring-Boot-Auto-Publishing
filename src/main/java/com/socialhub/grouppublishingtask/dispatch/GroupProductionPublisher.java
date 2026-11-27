package com.socialhub.grouppublishingtask.dispatch;

import java.util.UUID;

/** Integration contract. No implementation is shipped or auto-discovered.
 * A real provider must document authority for the exact group, verify current approval,
 * snapshot approved content/media, enforce rate limits and reconcile remote results.
 * The eventId is the stable provider idempotency key, if supported. Never fake success.
 */
public interface GroupProductionPublisher {
    record Request(GroupDispatchEnvelope envelope, UUID postId, String externalGroupId) {}
    enum Outcome { PUBLISHED, FAILED, NEEDS_REVIEW }
    record Result(Outcome outcome, String permalink) {}
    /** Exact workspace/group authorization, before staging new events. Read-only. */
    boolean isGroupAuthorized(UUID workspaceId, UUID groupId);
    /** Read-only, no publication. False or exception must block publication. */
    boolean isAuthorized(Request request);
    /** FAILED means provider definitively confirms no post was created.
     * Any ambiguous result must be NEEDS_REVIEW; no blind retry of remote side effects.
     */
    Result publish(Request request);
}
