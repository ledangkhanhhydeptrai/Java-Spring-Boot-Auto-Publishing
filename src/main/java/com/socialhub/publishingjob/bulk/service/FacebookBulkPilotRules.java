package com.socialhub.publishingjob.bulk.service;

import java.util.UUID;

/** Shared fail-closed eligibility for a controlled Bulk LIVE pilot. */
public final class FacebookBulkPilotRules {
    private FacebookBulkPilotRules() {}

    public static UUID runId(String value) {
        if (value == null || value.isBlank()) return null;
        UUID id = UUID.fromString(value.trim());
        if (!id.toString().equalsIgnoreCase(value.trim())) {
            throw new IllegalArgumentException("Invalid Facebook bulk pilot run ID");
        }
        return id;
    }

    // Both start validation and claim use this exact predicate (post alias p).
    // TEXT: zero media. IMAGE: only images, capped. VIDEO/REEL: exactly one media.
    // The existing publisher still validates each actual URL before any Meta call.
    public static final String MEDIA_ELIGIBLE = """
            NOT EXISTS (
                SELECT 1 FROM post_media m WHERE m.post_id = p.id
                AND (m.media_type IS NULL OR m.media_type NOT IN ('IMAGE','VIDEO','REEL')
                     OR NULLIF(BTRIM(m.public_url), '') IS NULL
                     OR m.public_url !~* '^https://[^/?#]+(/|[?]|$)'
                     OR (m.media_type = 'REEL' AND m.public_url !~* '^https://res[.]cloudinary[.]com/'))
            ) AND (
                (NOT EXISTS (SELECT 1 FROM post_media m WHERE m.post_id=p.id AND m.media_type <> 'IMAGE')
                 AND (SELECT COUNT(*) FROM post_media m WHERE m.post_id=p.id) <= ?)
                OR ((SELECT COUNT(*) FROM post_media m WHERE m.post_id=p.id) = 1
                    AND EXISTS (SELECT 1 FROM post_media m WHERE m.post_id=p.id AND
                         ((m.media_type='VIDEO' AND CAST(? AS boolean))
                          OR (m.media_type='REEL' AND CAST(? AS boolean)))))
            )
            """;
}
