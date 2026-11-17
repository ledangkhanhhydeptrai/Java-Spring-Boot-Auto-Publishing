-- MOCK-only, separate from production publishing_jobs and attempts.
CREATE TABLE IF NOT EXISTS mock_excel_runs (
  id UUID PRIMARY KEY,
  workspace_id UUID NOT NULL,
  import_id UUID NOT NULL,
  page VARCHAR(100) NOT NULL DEFAULT 'MOCK_PAGE_001',
  accounts_configured INTEGER NOT NULL CHECK (accounts_configured BETWEEN 1 AND 100),
  accounts_used INTEGER NOT NULL CHECK (accounts_used BETWEEN 1 AND 100),
  total INTEGER NOT NULL CHECK (total BETWEEN 1 AND 1000),
  completed INTEGER NOT NULL DEFAULT 0 CHECK (completed >= 0),
  failures INTEGER NOT NULL DEFAULT 0 CHECK (failures >= 0),
  state VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (workspace_id, import_id)
);
CREATE TABLE IF NOT EXISTS mock_excel_jobs (
  id UUID PRIMARY KEY,
  run_id UUID NOT NULL REFERENCES mock_excel_runs(id) ON DELETE CASCADE,
  source_row_number INTEGER NOT NULL,
  post_id UUID NOT NULL,
  account VARCHAR(10) NOT NULL,
  title TEXT,
  content TEXT NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
  completed_at TIMESTAMPTZ,
  UNIQUE (run_id, source_row_number)
);
CREATE INDEX IF NOT EXISTS idx_mock_excel_jobs_run ON mock_excel_jobs(run_id);
-- Messages from older RAM-only runs cannot be reconstructed after restart.
-- Preserve their identifiers for diagnostics instead of requeueing forever.
CREATE TABLE IF NOT EXISTS mock_excel_orphan_messages (
  payload TEXT PRIMARY KEY,
  first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
