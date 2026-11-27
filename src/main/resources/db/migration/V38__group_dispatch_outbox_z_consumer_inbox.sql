-- Additive, after group_dispatch_outbox. No events or tasks are created or updated.
-- Repeatable migration names sort lexically; outbox base must exist first.
CREATE TABLE IF NOT EXISTS group_dispatch_inbox (
    event_id UUID PRIMARY KEY REFERENCES group_dispatch_outbox(event_id) ON DELETE RESTRICT,
    task_id UUID NOT NULL UNIQUE REFERENCES group_publishing_tasks(id) ON DELETE RESTRICT,
    state VARCHAR(32) NOT NULL CHECK (state IN ('STARTED','PUBLISHED','FAILED','NEEDS_REVIEW')),
    execution_token UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    review_after TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    result_code VARCHAR(100),
    CONSTRAINT ck_group_dispatch_inbox_completion CHECK ((state = 'STARTED') = (completed_at IS NULL))
);
CREATE INDEX IF NOT EXISTS ix_group_dispatch_inbox_review ON group_dispatch_inbox(review_after,event_id) WHERE state='STARTED';
