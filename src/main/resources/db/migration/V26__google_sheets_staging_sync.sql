-- Google Sheets staging only. Does not create Posts or Publishing Jobs.
CREATE TABLE google_sheet_connections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    spreadsheet_id VARCHAR(150) NOT NULL,
    tab_gid VARCHAR(32) NOT NULL DEFAULT '0',
    tab_name VARCHAR(100),
    spreadsheet_url TEXT NOT NULL,
    auto_sync BOOLEAN NOT NULL DEFAULT FALSE,
    sync_status VARCHAR(20) NOT NULL DEFAULT 'NEW'
        CHECK (sync_status IN ('NEW','SYNCED','ERROR')),
    last_error TEXT,
    last_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_google_sheet_connection UNIQUE (workspace_id, spreadsheet_id, tab_gid)
);
CREATE INDEX idx_google_sheet_connections_auto ON google_sheet_connections(auto_sync) WHERE auto_sync = TRUE;

CREATE TABLE google_sheet_staged_rows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id UUID NOT NULL REFERENCES google_sheet_connections(id) ON DELETE CASCADE,
    source_row_number INTEGER NOT NULL CHECK (source_row_number >= 2),
    content TEXT NOT NULL DEFAULT '',
    topic TEXT NOT NULL DEFAULT '',
    image_1 TEXT NOT NULL DEFAULT '',
    image_2 TEXT NOT NULL DEFAULT '',
    source_publish_date TEXT NOT NULL DEFAULT '',
    source_status TEXT NOT NULL DEFAULT '',
    source_published_url TEXT NOT NULL DEFAULT '',
    row_hash VARCHAR(64) NOT NULL,
    publish_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    present_in_sheet BOOLEAN NOT NULL DEFAULT TRUE,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_google_sheet_row UNIQUE (connection_id,source_row_number)
);
CREATE INDEX idx_google_sheet_staged_eligible ON google_sheet_staged_rows(connection_id, publish_eligible) WHERE present_in_sheet;
