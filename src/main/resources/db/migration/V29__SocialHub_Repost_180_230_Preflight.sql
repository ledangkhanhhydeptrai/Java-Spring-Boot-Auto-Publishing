-- READ ONLY. Check precisely the 51 original Google Sheets rows before creating an explicit REPOST campaign.
-- No database mutations. Does not publish anything.
WITH target AS (
    SELECT r.source_row_number AS sheet_row,
           r.post_id,
           p.workspace_id,
           e.social_account_id,
           (SELECT count(*) FROM publishing_jobs j
            WHERE j.post_id=r.post_id AND j.social_account_id=e.social_account_id) AS existing_jobs,
           (SELECT count(*) FROM publishing_jobs j
            WHERE j.post_id=r.post_id AND j.social_account_id=e.social_account_id
              AND j.status='PUBLISHED') AS previous_published_jobs
    FROM excel_import_rows r
    JOIN excel_imports e ON e.id=r.import_id
    JOIN posts p ON p.id=r.post_id
    WHERE r.import_id='02ef32e1-986a-462d-a197-0f1c2ac6c0f9'::uuid
      AND e.workspace_id='5b98521c-1513-4039-936f-b17d8d8516db'::uuid
      AND e.social_account_id='f1b3e59e-33ae-4e6f-9dc6-8c1cf8ee2c7b'::uuid
      AND r.source_row_number BETWEEN 180 AND 230
)
SELECT sheet_row, post_id, existing_jobs, previous_published_jobs,
       CASE WHEN previous_published_jobs>0 THEN 'EXPLICIT_REPOST_REQUIRED'
            WHEN existing_jobs>0 THEN 'EXISTING_JOB_REVIEW'
            ELSE 'FIRST_PUBLISH' END AS planned_action
FROM target ORDER BY sheet_row;

-- Check exact coverage and duplicates before enabling any LIVE queue.
SELECT COUNT(*) AS rows_in_scope,
       COUNT(DISTINCT r.source_row_number) AS distinct_sheet_rows,
       MIN(r.source_row_number) AS first_row,
       MAX(r.source_row_number) AS last_row
FROM excel_import_rows r
WHERE r.import_id='02ef32e1-986a-462d-a197-0f1c2ac6c0f9'::uuid
  AND r.source_row_number BETWEEN 180 AND 230;

-- Ensure no older live Queue is still waiting before setting up repost.
SELECT id, status, requested_rows, created_at
FROM excel_orchestrator_queue
WHERE status IN ('QUEUED','RUNNING')
ORDER BY created_at DESC;
