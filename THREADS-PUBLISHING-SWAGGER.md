# SocialHub – Threads text publishing (manual Swagger test)

## Scope and safety

This update adds a manual, one-job-at-a-time **real Threads text publisher**. It does NOT enable the scheduler, bulk publishing, images, videos, token refresh or automatic retries. Facebook mock worker remains unchanged.

Requires the existing Threads OAuth connection, `threads_basic` and `threads_content_publish` permissions, and the **same** `THREADS_TOKEN_ENCRYPTION_KEY` used when saving the encrypted token. Do not rotate that key without migrating stored tokens.

Threads uses two calls: `POST https://graph.threads.net/v1.0/{user-id}/threads` (`media_type=TEXT`) then `POST .../{user-id}/threads_publish` (`creation_id`). The code sends access tokens as Bearer headers and never includes them in the API response.

## Test in Swagger

1. Keep `PUBLISHER_ENABLED=false`. Run PostgreSQL and Spring Boot with the existing `.env`/environment configuration.
2. Log in to Swagger; get CSRF token as usual.
3. `GET /api/workspaces/{workspaceId}/social-accounts`: copy the `id` of the connected `THREADS` account (NOT its external account ID).
4. `POST /api/workspaces/{workspaceId}/posts` with:
   ```json
   {"topic":"Threads smoke test","content":"SocialHub Threads API test","status":"READY","scheduledAt":null}
   ```
   Copy the returned post UUID. Keep text <= 500 Unicode code points and do not attach media.
5. `POST /api/posts/{postId}/jobs` with:
   ```json
   {"socialAccountId":"YOUR_THREADS_SOCIAL_ACCOUNT_UUID","scheduledAt":null}
   ```
   Copy the returned job UUID.
6. **Publishing is a real external side effect.** Only when ready to publish, call `POST /api/threads/publishing/jobs/{jobId}/publish-now` **once**. This requires your normal JWT cookie and CSRF header.
7. `PUBLISHED` means Meta returned a published media ID. `NEEDS_REVIEW` means Meta may have received the request; inspect your Threads account manually before taking any further action. `FAILED` means validation failed before any Meta request. Do not blindly retry or reset the job.
8. Confirm with `GET /api/workspaces/{workspaceId}/publishing-jobs` or the SQL below.

```sql
SELECT j.id, j.status, j.external_post_id, j.last_error, j.published_at,
       a.platform, a.display_name
FROM publishing_jobs j
JOIN social_accounts a ON a.id = j.social_account_id
WHERE a.platform = 'THREADS'
ORDER BY j.created_at DESC LIMIT 10;
```

## Notes

- The published numeric media ID is not necessarily a public Threads post shortcode. The `published_url` field is intentionally left NULL rather than creating a false URL.
- The existing Facebook scheduler only claims `FACEBOOK_PAGE` jobs. Threads jobs are currently manual-only.
- To avoid accidental duplicate posts, this endpoint only claims an untouched `PENDING` job (`attempt_count=0`) for a READY text-only post whose due time has passed. All failed/uncertain attempts require separate human review.
- Existing Flyway migrations are untouched. No new database columns are needed for this manual test.
- This source ZIP targets Java 25 as specified in `pom.xml`. Compilation and live publishing were not verified in the current Java 21 environment.
