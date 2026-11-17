CREATE TABLE publishing_reviews
(
    id                UUID PRIMARY KEY     DEFAULT gen_random_uuid(),

    publishing_job_id UUID        NOT NULL
        REFERENCES publishing_jobs (id) ON DELETE CASCADE,

    reviewed_by       UUID        NOT NULL
        REFERENCES users (id),

    decision          VARCHAR(30) NOT NULL,

    evidence          TEXT        NOT NULL,

    note              TEXT,

    external_post_id  VARCHAR(255),

    published_url     TEXT,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_publishing_review_decision
        CHECK (
            decision IN (
                         'CONFIRM_PUBLISHED',
                         'CONFIRM_NOT_PUBLISHED'
                )
            ),

    CONSTRAINT chk_publishing_review_evidence
        CHECK (LENGTH(TRIM(evidence)) > 0),

    CONSTRAINT chk_publishing_review_external_id
        CHECK (
            decision <> 'CONFIRM_PUBLISHED'
                OR (
                external_post_id IS NOT NULL
                    AND LENGTH(TRIM(external_post_id)) > 0
                )
            )
);

CREATE INDEX idx_publishing_reviews_job
    ON publishing_reviews (
                           publishing_job_id,
                           created_at DESC
        );

CREATE INDEX idx_publishing_reviews_user
    ON publishing_reviews (reviewed_by);
