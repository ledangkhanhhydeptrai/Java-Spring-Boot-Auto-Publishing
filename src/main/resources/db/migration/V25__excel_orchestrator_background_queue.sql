-- A durable, single-confirmation orchestration queue. This tracks job preparation,
-- NOT the eventual Facebook publishing result.
CREATE TABLE excel_orchestrator_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    import_id UUID NOT NULL REFERENCES excel_imports(id),
    operator_id UUID NOT NULL REFERENCES users(id),
    request_payload JSONB NOT NULL,
    requested_rows INT NOT NULL CHECK (requested_rows BETWEEN 1 AND 50),
    processed_rows INT NOT NULL DEFAULT 0 CHECK (processed_rows >= 0),
    created_jobs INT NOT NULL DEFAULT 0 CHECK (created_jobs >= 0),
    prepared_jobs INT NOT NULL DEFAULT 0 CHECK (prepared_jobs >= 0),
    started_runs INT NOT NULL DEFAULT 0 CHECK (started_runs >= 0),
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED'
      CHECK (status IN ('QUEUED','RUNNING','DISPATCHED','REVIEW_REQUIRED')),
    last_result JSONB,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Protect one live orchestrator run per import at a time; a review run
-- also blocks re-confirmation until manually investigated.
CREATE UNIQUE INDEX uq_excel_orchestrator_one_active_import
    ON excel_orchestrator_queue(import_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'REVIEW_REQUIRED');
CREATE INDEX idx_excel_orchestrator_queue_status
    ON excel_orchestrator_queue(status, created_at);
CREATE TABLE excel_orchestrator_bulk_runs (
    queue_id UUID NOT NULL REFERENCES excel_orchestrator_queue(id),
    bulk_run_id UUID NOT NULL UNIQUE REFERENCES facebook_bulk_runs(id),
    PRIMARY KEY (queue_id, bulk_run_id)
);
