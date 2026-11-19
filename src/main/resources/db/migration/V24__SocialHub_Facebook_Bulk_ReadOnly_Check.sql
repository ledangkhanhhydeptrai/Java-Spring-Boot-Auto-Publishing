-- SocialHub / Facebook Bulk Publishing — read-only verification (PostgreSQL 17)
-- SAFE: All statements are SELECT. No jobs are created, started or deleted.
-- Based on AccountVaultBulkPublishingService.java schema, 18 Nov 2026.

-- 1) Recent Facebook Bulk Runs and each job's progress.
SELECT
    bj.bulk_run_id,
    j.id                 AS publishing_job_id,
    j.post_id,
    j.social_account_id,
    j.status             AS job_status,
    j.scheduled_at,
    j.facebook_connection_id
FROM facebook_bulk_run_jobs AS bj
JOIN publishing_jobs AS j
  ON j.id = bj.publishing_job_id
ORDER BY j.scheduled_at DESC
LIMIT 100;

-- 2) Summary per Bulk Run (compare against Frontend Progress).
SELECT
    bj.bulk_run_id,
    COUNT(*) AS total,
    COUNT(*) FILTER (WHERE j.status = 'PENDING')      AS pending,
    COUNT(*) FILTER (WHERE j.status = 'PROCESSING')   AS processing,
    COUNT(*) FILTER (WHERE j.status = 'PUBLISHED')    AS published,
    COUNT(*) FILTER (WHERE j.status = 'FAILED')       AS failed,
    COUNT(*) FILTER (WHERE j.status = 'NEEDS_REVIEW') AS needs_review,
    MIN(j.scheduled_at) AS first_scheduled_at,
    MAX(j.scheduled_at) AS last_scheduled_at
FROM facebook_bulk_run_jobs AS bj
JOIN publishing_jobs AS j
  ON j.id = bj.publishing_job_id
GROUP BY bj.bulk_run_id
ORDER BY last_scheduled_at DESC
LIMIT 30;

-- 3) Safety check: missing/mismatched Facebook OAuth bindings.
-- A nonzero unsafe_job_count means LIVE must NOT be started.
SELECT
    bj.bulk_run_id,
    COUNT(*) FILTER (WHERE
        a.id IS NULL OR
        a.status IS DISTINCT FROM 'CONNECTED' OR
        j.facebook_connection_id IS NULL OR
        c.id IS NULL OR
        c.social_account_id IS DISTINCT FROM a.id OR
        c.status IS DISTINCT FROM 'CONNECTED' OR
        NULLIF(BTRIM(c.access_token_encrypted), '') IS NULL OR
        (c.token_expires_at IS NOT NULL AND c.token_expires_at <= j.scheduled_at)
    ) AS unsafe_job_count,
    COUNT(*) AS total_job_count
FROM facebook_bulk_run_jobs AS bj
JOIN publishing_jobs AS j
  ON j.id = bj.publishing_job_id
LEFT JOIN social_accounts AS a
  ON a.id = j.social_account_id
LEFT JOIN facebook_page_connections AS c
  ON c.id = j.facebook_connection_id
GROUP BY bj.bulk_run_id
ORDER BY MAX(j.scheduled_at) DESC
LIMIT 30;
