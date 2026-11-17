# SocialHub — Threads scheduled publishing and bulk run

## Changes
- Excel imports accept CONNECTED THREADS accounts, with text length validation (1–500 Unicode code points). Excel columns: `topic`, `content`, `scheduled_at`, `social_account_id`. Dates are `yyyy-MM-dd HH:mm` in Asia/Ho_Chi_Minh and must be in the future.
- The existing PublisherScheduler invokes ThreadsPublishingService only when BOTH `PUBLISHER_ENABLED=true` and `THREADS_PUBLISHER_ENABLED=true`. Both default to false.
- Threads service handles `READY` and `SCHEDULED` posts, using an atomic database claim (`PENDING`, attempt_count=0, due now). Unknown API results go to `NEEDS_REVIEW`, never automatically retried.
- `POST /api/workspaces/{workspaceId}/threads/bulk-run` accepts an explicit set of 1–100 eligible job IDs and `confirmPublish:true`, and changes their schedule to now. It does not send requests itself; scheduler must be enabled.
- Campaign-linked jobs only run if their campaign is RUNNING.

## Safe test sequence
1. Back up PostgreSQL and verify the Threads account is CONNECTED. Keep both publisher switches **false**.
2. Import an `.xlsx` containing ONE newly created, non-mock text row, scheduled 5–10 minutes in the future. Use your real THREADS social_account_id, not external_account_id.
3. Inspect the new post and publishing_job in Swagger/DB. Ensure the content, target account and schedule are correct.
4. Enable `PUBLISHER_ENABLED=true`, `THREADS_PUBLISHER_ENABLED=true` and restart; watch logs. The scheduler will process ALL eligible due Threads jobs, so check for old pending jobs first.
5. Verify the actual post on Threads, plus publishing_jobs and publishing_attempts status. Disable the switches after the test.
6. For bulk, with switches OFF, prepare 1–3 **new** pending Threads jobs, call `bulk-run` with explicit IDs and `confirmPublish:true`, inspect queued jobs, then enable switches to publish.

Example bulk JSON:
```json
{
  "jobIds": ["00000000-0000-0000-0000-000000000001"],
  "confirmPublish": true
}
```
Replace the example ID with a real eligible job ID. This endpoint does not bypass scheduled safety checks, authentication or CSRF.

## Known limitations / precautions
- This release supports Threads TEXT only, not image/video.
- A failed/unknown Threads publication is not automatically retried. Review on Threads before any manual correction.
- `bulk-run` advances the selected job schedule to now; it does not modify the associated Post scheduled_at.
- There is no per-import or per-campaign allowlist for the scheduler; do not enable against a database containing unreviewed due jobs.
- Compile and integration tests must be run on your Java 25 + Maven development machine. This packaging environment does not have Maven or JDK 25; no successful compile claim is made.
