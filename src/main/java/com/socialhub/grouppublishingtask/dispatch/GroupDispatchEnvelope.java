package com.socialhub.grouppublishingtask.dispatch;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable identity across broker retries; no post content, credentials or external URLs. */
public record GroupDispatchEnvelope(UUID eventId, UUID taskId, UUID workspaceId, UUID groupId) {
    public static final String TYPE = "socialhub.group.dispatch.v1";
    public static final String CONTENT_TYPE = "text/plain";
    public GroupDispatchEnvelope {
        Objects.requireNonNull(eventId); Objects.requireNonNull(taskId);
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(groupId);
    }
    public byte[] encode() {
        return (eventId + "|" + taskId + "|" + workspaceId + "|" + groupId).getBytes(StandardCharsets.US_ASCII);
    }
    public static GroupDispatchEnvelope decode(byte[] body) {
        if (body == null || body.length != 147) throw new IllegalArgumentException("Invalid envelope length");
        String[] ids = new String(body, StandardCharsets.US_ASCII).split("\\|", -1);
        if (ids.length != 4) throw new IllegalArgumentException("Invalid envelope fields");
        return new GroupDispatchEnvelope(parse(ids[0]), parse(ids[1]), parse(ids[2]), parse(ids[3]));
    }
    private static UUID parse(String raw) {
        UUID value = UUID.fromString(raw);
        if (!value.toString().equals(raw)) throw new IllegalArgumentException("Noncanonical UUID");
        return value;
    }
    public static int retryDelaySeconds(int attempt) {
        if (attempt < 1 || attempt > 20) throw new IllegalArgumentException("Invalid attempt");
        return Math.min(900, 15 * (1 << Math.min(6, attempt - 1)));
    }
}
