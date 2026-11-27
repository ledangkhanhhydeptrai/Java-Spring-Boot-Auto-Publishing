-- Dedicated, durable Facebook identity synchronization queue. No publishing Job updates.
CREATE TABLE facebook_identity_sync_runs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_fb_identity_sync_active_workspace ON facebook_identity_sync_runs(workspace_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_fb_identity_sync_latest ON facebook_identity_sync_runs(workspace_id, created_at DESC, id DESC);
CREATE TABLE facebook_identity_sync_items (
    run_id UUID NOT NULL REFERENCES facebook_identity_sync_runs(id) ON DELETE CASCADE,
    facebook_user_id VARCHAR(128) NOT NULL,
    display_name TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','SKIPPED','FAILED')),
    claim_id UUID,
    lease_until TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    saved_pages INTEGER NOT NULL DEFAULT 0,
    missing_pages INTEGER NOT NULL DEFAULT 0,
    disconnected_pages INTEGER NOT NULL DEFAULT 0,
    message TEXT,
    PRIMARY KEY (run_id, facebook_user_id)
);
CREATE INDEX ix_fb_identity_sync_items_status ON facebook_identity_sync_items(status, run_id, facebook_user_id);
CREATE INDEX ix_fb_identity_sync_items_finished ON facebook_identity_sync_items(finished_at DESC) WHERE status = 'FAILED';
CREATE INDEX ix_fb_identity_sync_recent ON facebook_identity_sync_items(finished_at DESC) WHERE finished_at IS NOT NULL;
