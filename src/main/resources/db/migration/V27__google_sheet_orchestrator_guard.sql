-- Queue provenance and immutable sheet rows approved for the original queue.
-- No publishing or data migration is performed by this DDL.
CREATE TABLE google_sheet_queue_bindings (
    queue_id UUID PRIMARY KEY REFERENCES excel_orchestrator_queue(id) ON DELETE CASCADE,
    connection_id UUID NOT NULL REFERENCES google_sheet_connections(id),
    workspace_id UUID NOT NULL,
    import_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE google_sheet_queue_rows (
    queue_id UUID NOT NULL REFERENCES google_sheet_queue_bindings(queue_id) ON DELETE CASCADE,
    source_row_number INTEGER NOT NULL,
    post_id UUID NOT NULL REFERENCES posts(id),
    row_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY(queue_id, source_row_number),
    UNIQUE(queue_id, post_id)
);
CREATE INDEX idx_google_sheet_queue_rows_queue ON google_sheet_queue_rows(queue_id);
