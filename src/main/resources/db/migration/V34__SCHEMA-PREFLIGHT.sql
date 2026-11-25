-- READ-ONLY. Run in DBeaver on the same database as your current SocialHub Backend.
SELECT table_name,column_name,data_type,is_nullable,column_default
FROM information_schema.columns
WHERE table_schema='public'
  AND table_name IN ('excel_import_rows','excel_imports','posts','post_media',
     'facebook_groups','group_publishing_tasks',
     'google_sheet_connections','google_sheet_import_links','google_sheet_staged_rows')
ORDER BY table_name,ordinal_position;

-- READ-ONLY: check existing Group Task for the source import, without creating duplicates.
SELECT r.source_row_number,r.post_id,t.id AS group_task_id,t.status,t.published_url
FROM excel_import_rows r
JOIN excel_imports e ON e.id=r.import_id
LEFT JOIN group_publishing_tasks t ON t.post_id=r.post_id
WHERE e.id='d54b47fe-2dd3-468b-b985-3ad7d97329e5'
  AND e.workspace_id='cd8c00ba-2107-4ad4-8547-db7c07332eb1'
ORDER BY r.source_row_number,t.id LIMIT 50;
