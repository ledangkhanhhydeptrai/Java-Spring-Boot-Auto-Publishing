-- Apply ONLY to the current demo workspace after deploying the patched Java service.
-- Re-runnable; no changes to posts, publishing_jobs, excel_import_rows or sheet cells.
BEGIN;
DO $$
DECLARE
    v_workspace CONSTANT uuid := '07d06e94-1a45-47e0-84c5-7574c02436bd';
    v_connection CONSTANT uuid := '34163598-7ffd-460d-adb6-5cdb7e722b7e';
    v_files integer;
    v_names integer;
    v_rows integer;
    v_matches integer;
    v_distinct_sheet integer;
    v_distinct_posts integer;
    v_enabled integer;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM google_sheet_connections c
        WHERE c.id = v_connection AND c.workspace_id = v_workspace
          AND c.spreadsheet_id = '1gqgCS_UZ91nhRu1TBi8gXRHZKZcMyhOD-63WQNPVwyo'
          AND c.tab_name = 'Trang tính1'
          AND c.writeback_enabled IS TRUE AND c.sync_status = 'SYNCED'
    ) THEN RAISE EXCEPTION 'Expected synced writeback connection not found'; END IF;

    -- Refuse concurrent writeback to the same sheet/tab from another connection.
    IF EXISTS (
        SELECT 1 FROM google_sheet_connections c
        JOIN google_sheet_connections old ON old.spreadsheet_id = c.spreadsheet_id
            AND old.tab_name = c.tab_name AND old.id <> c.id
        JOIN google_sheet_import_links l ON l.connection_id = old.id
        WHERE c.id = v_connection AND old.writeback_enabled IS TRUE AND l.enabled IS TRUE
    ) THEN RAISE EXCEPTION 'Another connection has enabled links for this sheet'; END IF;

    SELECT count(*), count(DISTINCT file_name) INTO v_files, v_names
    FROM excel_imports WHERE workspace_id = v_workspace
      AND file_name ~ '^SH26_A(0[1-9]|10)[.]xlsx$'
      AND imported_rows = 100 AND social_account_id IS NOT NULL;
    IF v_files <> 10 OR v_names <> 10 THEN
        RAISE EXCEPTION 'Expected 10 distinct SH26 imports, found % / %', v_files, v_names;
    END IF;

    IF EXISTS (
        SELECT 1 FROM google_sheet_import_links l
        JOIN excel_imports e ON e.id = l.import_id AND e.workspace_id = l.workspace_id
        WHERE e.workspace_id = v_workspace AND l.enabled IS TRUE
          AND ((e.file_name ~ '^SH26_A(0[1-9]|10)[.]xlsx$' AND l.connection_id <> v_connection)
            OR (l.connection_id = v_connection AND e.file_name !~ '^SH26_A(0[1-9]|10)[.]xlsx$'))
    ) THEN RAISE EXCEPTION 'Unexpected enabled import link; review before continuing'; END IF;

    -- Match exact content, never change the local Excel source row number.
    WITH matched AS (
        SELECT e.id AS import_id, r.source_row_number, p.id AS post_id,
               count(s.source_row_number) AS hits, min(s.source_row_number) AS sheet_row
        FROM excel_imports e
        JOIN excel_import_rows r ON r.import_id = e.id
        JOIN posts p ON p.id = r.post_id AND p.workspace_id = e.workspace_id
        LEFT JOIN google_sheet_staged_rows s ON s.connection_id = v_connection
            AND s.present_in_sheet IS TRUE AND s.content = p.content
        WHERE e.workspace_id = v_workspace
          AND e.file_name ~ '^SH26_A(0[1-9]|10)[.]xlsx$'
        GROUP BY e.id, r.source_row_number, p.id
    )
    SELECT count(*), count(*) FILTER (WHERE hits = 1 AND sheet_row BETWEEN 2 AND 10001),
           count(DISTINCT sheet_row) FILTER (WHERE hits = 1), count(DISTINCT post_id)
    INTO v_rows, v_matches, v_distinct_sheet, v_distinct_posts FROM matched;
    IF v_rows <> 1000 OR v_matches <> 1000 OR v_distinct_sheet <> 1000 OR v_distinct_posts <> 1000 THEN
        RAISE EXCEPTION 'Mapping not bijective: rows %, matches %, sheet rows %, posts %',
            v_rows, v_matches, v_distinct_sheet, v_distinct_posts;
    END IF;

    IF EXISTS (
        SELECT e.id FROM excel_imports e
        LEFT JOIN excel_import_rows r ON r.import_id = e.id
        WHERE e.workspace_id = v_workspace AND e.file_name ~ '^SH26_A(0[1-9]|10)[.]xlsx$'
        GROUP BY e.id HAVING count(r.source_row_number) <> 100
            OR count(DISTINCT r.source_row_number) <> 100
    ) THEN RAISE EXCEPTION 'An import does not contain exactly 100 unique local rows'; END IF;

    INSERT INTO google_sheet_import_links (workspace_id, connection_id, import_id, enabled)
    SELECT e.workspace_id, v_connection, e.id, TRUE
    FROM excel_imports e
    WHERE e.workspace_id = v_workspace AND e.file_name ~ '^SH26_A(0[1-9]|10)[.]xlsx$'
      AND NOT EXISTS (SELECT 1 FROM google_sheet_import_links l
          WHERE l.workspace_id = v_workspace AND l.connection_id = v_connection AND l.import_id = e.id)
    ON CONFLICT DO NOTHING;

    UPDATE google_sheet_import_links l SET enabled = TRUE, updated_at = now()
    FROM excel_imports e
    WHERE l.workspace_id = v_workspace AND l.connection_id = v_connection
      AND l.import_id = e.id AND e.workspace_id = v_workspace
      AND e.file_name ~ '^SH26_A(0[1-9]|10)[.]xlsx$' AND l.enabled IS FALSE;

    SELECT count(*) INTO v_enabled FROM google_sheet_import_links l
    JOIN excel_imports e ON e.id = l.import_id AND e.workspace_id = l.workspace_id
    WHERE l.workspace_id = v_workspace AND l.connection_id = v_connection
      AND e.file_name ~ '^SH26_A(0[1-9]|10)[.]xlsx$' AND l.enabled IS TRUE;
    IF v_enabled <> 10 THEN RAISE EXCEPTION 'Expected 10 enabled links, found %', v_enabled; END IF;
    RAISE NOTICE 'Verified 1000 unique matches; 10 enabled links; no publishing data modified';
END $$;
COMMIT;

SELECT e.file_name, e.imported_rows, l.enabled
FROM google_sheet_import_links l JOIN excel_imports e ON e.id = l.import_id
WHERE l.workspace_id = '07d06e94-1a45-47e0-84c5-7574c02436bd'
  AND l.connection_id = '34163598-7ffd-460d-adb6-5cdb7e722b7e'
ORDER BY e.file_name;
