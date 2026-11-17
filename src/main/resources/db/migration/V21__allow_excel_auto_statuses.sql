-- Flyway: preserve EVERY status allowed by the existing database constraint,
-- and permit the two additional status values written by ExcelAutoPublishService.
-- Non-destructive; does not alter or delete existing Excel rows.
DO $migration$
DECLARE
    old_condition text;
BEGIN
    SELECT pg_get_expr(c.conbin, c.conrelid)
    INTO old_condition
    FROM pg_constraint c
    WHERE c.conrelid = 'public.excel_import_rows'::regclass
      AND c.conname = 'chk_excel_processing_status'
      AND c.contype = 'c';

    IF old_condition IS NULL THEN
        RAISE EXCEPTION 'Expected constraint chk_excel_processing_status not found';
    END IF;

    EXECUTE 'ALTER TABLE public.excel_import_rows DROP CONSTRAINT chk_excel_processing_status';
    EXECUTE format(
        'ALTER TABLE public.excel_import_rows ADD CONSTRAINT chk_excel_processing_status CHECK ((%s) OR processing_status IN (''AUTO_BLOCKED'', ''AUTO_SCHEDULED''))',
        old_condition
    );
END;
$migration$;
