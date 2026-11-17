-- Preserve source import rows; only mark as published after actual worker success.
ALTER TABLE excel_import_rows
    DROP CONSTRAINT chk_excel_processing_status;
ALTER TABLE excel_import_rows
    ADD CONSTRAINT chk_excel_processing_status CHECK (
        processing_status IN ('IMPORTED','PUBLISHED',
          'SKIPPED_ALREADY_PUBLISHED','SKIPPED_PAST_DUE','SKIPPED_INVALID')
    );
ALTER TABLE excel_import_rows
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN published_url TEXT;
CREATE INDEX idx_excel_import_rows_published ON excel_import_rows(import_id, processing_status);

-- Backfill rows for jobs published before this migration.
UPDATE excel_import_rows e
SET processing_status = 'PUBLISHED',
    published_at = j.published_at,
    published_url = j.published_url
FROM publishing_jobs j
WHERE j.post_id = e.post_id
  AND j.status = 'PUBLISHED'
  AND e.processing_status = 'IMPORTED';
