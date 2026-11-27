package com.socialhub.grouppublishingtask.queue;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Versioned diagnostic envelope. It cannot represent a real publishing task. */
public record GroupTaskMessage(UUID messageId, UUID workspaceId, UUID facebookGroupId) {
    public static final String TYPE = "GROUP_QUEUE_TEST_V1";
    public static final String CONTENT_TYPE = "text/plain";
    private static final int WIRE_LENGTH = TYPE.length() + 3 + 3 * 36;

    public GroupTaskMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(facebookGroupId, "facebookGroupId");
    }

    public static GroupTaskMessage diagnostic(UUID workspaceId, UUID facebookGroupId) {
        return new GroupTaskMessage(UUID.randomUUID(), workspaceId, facebookGroupId);
    }

    public byte[] encode() {
        return (TYPE + "|" + messageId + "|" + workspaceId + "|" + facebookGroupId)
                .getBytes(StandardCharsets.US_ASCII);
    }

    public static GroupTaskMessage decode(byte[] body) {
        if (body == null || body.length != WIRE_LENGTH) {
            throw new IllegalArgumentException("Invalid diagnostic message length");
        }
        String[] fields = new String(body, StandardCharsets.US_ASCII).split("\\|", -1);
        if (fields.length != 4 || !TYPE.equals(fields[0])) {
            throw new IllegalArgumentException("Unsupported diagnostic message type");
        }
        return new GroupTaskMessage(canonicalUuid(fields[1]), canonicalUuid(fields[2]), canonicalUuid(fields[3]));
    }

    private static UUID canonicalUuid(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("Non-canonical UUID");
        }
        return parsed;
    }
}
