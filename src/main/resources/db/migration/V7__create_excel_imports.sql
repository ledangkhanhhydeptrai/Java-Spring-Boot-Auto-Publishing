
-- V7: Create Excel import history

CREATE TABLE excel_imports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    workspace_id UUID NOT NULL,

    file_name VARCHAR(255) NOT NULL,
    file_hash VARCHAR(64) NOT NULL,

    total_rows INTEGER NOT NULL DEFAULT 0,
    imported_rows INTEGER NOT NULL DEFAULT 0,
    skipped_rows INTEGER NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_excel_import_workspace
        FOREIGN KEY (workspace_id)
        REFERENCES workspaces(id),

    CONSTRAINT uq_excel_import_workspace_hash
        UNIQUE (workspace_id, file_hash),

    CONSTRAINT ck_excel_import_counts
        CHECK (
            total_rows >= 0
            AND imported_rows >= 0
            AND skipped_rows >= 0
        )
);

CREATE INDEX idx_excel_import_workspace
    ON excel_imports(workspace_id);
