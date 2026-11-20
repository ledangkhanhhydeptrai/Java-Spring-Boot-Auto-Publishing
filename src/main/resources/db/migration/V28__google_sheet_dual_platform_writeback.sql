-- Optional, opt-in writeback for Google Sheets. Never changes the legacy G/H source fields.
ALTER TABLE google_sheet_connections
  ADD COLUMN IF NOT EXISTS writeback_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS google_sheet_writeback_audit (
  connection_id UUID NOT NULL REFERENCES google_sheet_connections(id) ON DELETE CASCADE,
  source_row_number INTEGER NOT NULL CHECK (source_row_number >= 2),
  platform VARCHAR(20) NOT NULL CHECK (platform IN ('FACEBOOK_PAGE','THREADS')),
  external_post_id TEXT,
  published_url TEXT,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(connection_id,source_row_number,platform)
);
