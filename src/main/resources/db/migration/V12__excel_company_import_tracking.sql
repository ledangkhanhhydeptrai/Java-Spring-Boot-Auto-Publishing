
-- V12: Company Excel import tracking

-- 1. Add selected social account and import metadata.
ALTER TABLE excel_imports
    ADD COLUMN social_account_id UUID
        REFERENCES social_accounts(id);

ALTER TABLE excel_imports
    ADD COLUMN publish_time TIME;

ALTER TABLE excel_imports
    ADD COLUMN source_sheet_name VARCHAR(255);

-- 2. Replace workspace-wide duplicate rule
-- with per-account duplicate protection.
ALTER TABLE excel_imports
    DROP CONSTRAINT uq_excel_import_workspace_hash;

ALTER TABLE excel_imports
    ADD CONSTRAINT uq_excel_import_account_hash
    UNIQUE (
        workspace_id,
        social_account_id,
        file_hash
    );

-- 3. Track every source Excel row.
CREATE TABLE excel_import_rows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    import_id UUID NOT NULL
        REFERENCES excel_imports(id) ON DELETE CASCADE,

    source_row_number INTEGER NOT NULL,

    source_status VARCHAR(30) NOT NULL,

    processing_status VARCHAR(40) NOT NULL,

    reason TEXT,

    post_id UUID REFERENCES posts(id),

    source_published_url TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_excel_import_row
        UNIQUE (import_id, source_row_number),

    CONSTRAINT chk_excel_source_row_positive
        CHECK (source_row_number > 0),

    CONSTRAINT chk_excel_processing_status
        CHECK (
            processing_status IN (
                'IMPORTED',
                'SKIPPED_ALREADY_PUBLISHED',
                'SKIPPED_PAST_DUE',
                'SKIPPED_INVALID'
            )
        )
);

CREATE INDEX idx_excel_import_rows_import
    ON excel_import_rows(import_id);

CREATE INDEX idx_excel_import_rows_post
    ON excel_import_rows(post_id);

CREATE INDEX idx_excel_imports_account
    ON excel_imports(social_account_id);
