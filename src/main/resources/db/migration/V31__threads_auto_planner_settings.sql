-- Rename V__ to the next unused Flyway version, e.g. V27__...
CREATE TABLE IF NOT EXISTS threads_auto_planner_settings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES workspaces(id),
  import_id UUID NOT NULL REFERENCES excel_imports(id),
  threads_account_id UUID NOT NULL REFERENCES social_accounts(id),
  operator_id UUID NOT NULL REFERENCES users(id),
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  from_row INTEGER NOT NULL DEFAULT 2 CHECK (from_row >= 2),
  to_row INTEGER NOT NULL DEFAULT 10001 CHECK (to_row >= from_row AND to_row <= 10001),
  max_rows_per_run INTEGER NOT NULL DEFAULT 5 CHECK (max_rows_per_run BETWEEN 1 AND 5),
  interval_minutes INTEGER NOT NULL DEFAULT 2 CHECK (interval_minutes BETWEEN 1 AND 1440),
  start_delay_minutes INTEGER NOT NULL DEFAULT 15 CHECK (start_delay_minutes BETWEEN 10 AND 1440),
  poll_minutes INTEGER NOT NULL DEFAULT 10 CHECK (poll_minutes BETWEEN 1 AND 1440),
  next_run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_run_at TIMESTAMPTZ,
  last_error TEXT,
  last_created INTEGER,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_threads_auto_workspace_import_account UNIQUE(workspace_id, import_id, threads_account_id)
);
CREATE INDEX IF NOT EXISTS ix_threads_auto_due
 ON threads_auto_planner_settings(next_run_at) WHERE enabled;
