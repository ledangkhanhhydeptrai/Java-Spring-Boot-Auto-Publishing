-- Persist exact source date; never schedule from the current date as a fallback.
ALTER TABLE excel_import_rows ADD COLUMN source_publish_date DATE;
CREATE INDEX IF NOT EXISTS idx_excel_import_rows_import_source
    ON excel_import_rows (import_id, source_row_number);
