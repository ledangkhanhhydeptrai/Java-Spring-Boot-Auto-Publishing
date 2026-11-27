-- Additive, repeatable Flyway migration. No existing Task or Post is changed.
CREATE TABLE IF NOT EXISTS group_sheet_writeback_targets (
    connection_id UUID PRIMARY KEY REFERENCES google_sheet_connections(id) ON DELETE CASCADE,
    facebook_group_id UUID NOT NULL REFERENCES facebook_groups(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL,
    spreadsheet_id TEXT NOT NULL,
    tab_name TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_group_writeback_sheet_tab UNIQUE (spreadsheet_id, tab_name)
);
