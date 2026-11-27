-- Additive phase 1. No existing rows are updated, and no event is staged.
CREATE TABLE IF NOT EXISTS group_dispatch_outbox (
    event_id UUID PRIMARY KEY,
    task_id UUID NOT NULL UNIQUE REFERENCES group_publishing_tasks(id) ON DELETE RESTRICT,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    broker_confirmed_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error_code VARCHAR(100),
    CONSTRAINT ck_group_dispatch_outbox_state CHECK (state IN ('PENDING','BROKER_CONFIRMED','DEAD')),
    CONSTRAINT ck_group_dispatch_outbox_confirmed CHECK ((state = 'BROKER_CONFIRMED') = (broker_confirmed_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS ix_group_dispatch_outbox_pending
    ON group_dispatch_outbox (next_attempt_at, event_id) WHERE state = 'PENDING';
