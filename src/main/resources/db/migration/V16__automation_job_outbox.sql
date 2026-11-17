-- Outbox is a delivery ledger; this phase does not mutate publishing_jobs state.
-- Explicit cutoff on candidate selection keeps legacy pending LIVE jobs untouched.
CREATE TABLE automation_job_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    publishing_job_id UUID NOT NULL UNIQUE REFERENCES publishing_jobs(id) ON DELETE CASCADE,
    platform VARCHAR(30) NOT NULL CHECK (platform IN ('FACEBOOK_PAGE', 'THREADS')),
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING','SENDING','SENT','OBSERVED')),
    delivery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMPTZ,
    observed_at TIMESTAMPTZ
);
CREATE INDEX idx_automation_job_outbox_ready
    ON automation_job_outbox(next_attempt_at, created_at)
    WHERE state IN ('PENDING','SENDING');
