-- Durable Meta Reel identity. No outbound publish retries are authorized by this table.
CREATE TABLE IF NOT EXISTS facebook_reel_publications (
    publishing_job_id UUID NOT NULL REFERENCES publishing_jobs(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    meta_video_id VARCHAR(64) NOT NULL CHECK (meta_video_id ~ '^[0-9]+$'),
    phase VARCHAR(30) NOT NULL CHECK (phase IN ('START_ACCEPTED','UPLOAD_ACCEPTED','FINISH_ACCEPTED','VERIFIED_PUBLISHED')),
    last_checked_at TIMESTAMPTZ,
    last_status VARCHAR(120),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (publishing_job_id, attempt_number),
    UNIQUE(meta_video_id)
);
CREATE INDEX IF NOT EXISTS idx_reel_verify_phase ON facebook_reel_publications(phase, last_checked_at);
