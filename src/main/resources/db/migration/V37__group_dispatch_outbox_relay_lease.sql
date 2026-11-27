-- Additive; Phase 1 migration must already be installed. No events created.
ALTER TABLE group_dispatch_outbox ADD COLUMN IF NOT EXISTS lease_token UUID;
ALTER TABLE group_dispatch_outbox ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS ix_group_dispatch_outbox_lease
    ON group_dispatch_outbox (lease_until, next_attempt_at, event_id) WHERE state = 'PENDING';
