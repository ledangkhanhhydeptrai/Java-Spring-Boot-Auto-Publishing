# SocialHub Phase 2C — Two terminals (source-code patch)

**This is an integration patch, NOT a compiled or end-to-end-tested release.** The uploaded backend already contains `AutomationOutboxDispatcher`, `AutomationLiveConsumer`, `AutomationExactJobExecutor`, `FanpageQueueProcessor` and RabbitMQ queues. We did not create a second publisher.

## Changes
- `app.runtime.role=api` enables Dispatcher and legacy Scheduler components only in API process.
- `app.runtime.role=worker` enables LIVE RabbitMQ consumers only in Worker process; HTTP listener is disabled via `server.port=-1` (servlet/security context remains active).
- `RABBITMQ_LISTENER_ENABLED` remains OFF by default, LIVE execution requires explicit opt-in.
- New `application-api.yml`, `application-worker.yml`, `start-api.ps1`, `start-worker.ps1`.
- No Excel, Approval, Post or PublishingJob business logic was rewritten.

## Prerequisites
- Java 25, Maven command `mvn`, PostgreSQL and RabbitMQ.
- DATABASE/CLOUDINARY/ENCRYPTION/OAUTH environment variables from your **existing** working environment. Never commit secrets.
- `FACEBOOK_PUBLISHING_MODE=LIVE`, `META_BASE_URL=https://graph.facebook.com` to use LIVE; valid authorized Page token and supported Meta API permissions.
- VERY IMPORTANT: test using a dedicated database or a verified single authorized Job first.

## Starting in PowerShell (two windows, from project root)
Terminal 1 (safe: dispatcher disabled, API only):
```
.\start-api.ps1 -StartAfterUtc '2026-11-16T09:00:00Z'
```
Terminal 2 (safe: listener disabled, Worker process only):
```
.\start-worker.ps1
```

To actually allow public posting, verify the target Page, post and UTC cutoff first. Restart both processes in opt-in mode:
```
# API terminal: stage and dispatch eligible jobs CREATED AT/AFTER the cutoff
.\start-api.ps1 -StartAfterUtc '2026-11-16T09:00:00Z' -EnableLiveDispatch
# Worker terminal: set the two env vars before running
$env:FACEBOOK_PUBLISHING_MODE='LIVE'
$env:META_BASE_URL='https://graph.facebook.com'
.\start-worker.ps1 -EnableLive
```

**Pick a cutoff that intentionally includes the designated newly-created Job only.** All other eligible jobs created on or after cutoff may publish too. SQL selection also refuses initial jobs >5 min overdue.

## Status SQL (read-only)
```
SELECT j.id,j.status,j.scheduled_at,j.attempt_count,j.last_error,j.published_url,
       p.status AS post_status,p.approval_status,a.platform,a.external_account_id
FROM publishing_jobs j JOIN posts p ON p.id=j.post_id
JOIN social_accounts a ON a.id=j.social_account_id
ORDER BY j.created_at DESC LIMIT 20;

SELECT state,platform,COUNT(*) FROM automation_job_outbox GROUP BY state,platform ORDER BY state,platform;
```

## Important known issues before production
1. Outbox dispatcher currently calls `RabbitTemplate.convertAndSend()` then marks event `SENT` without broker publisher confirmation, and no robust reconciliation of lost `SENT` events. **Not production safe yet.** Do not interpret two-terminal startup as proof of guaranteed delivery.
2. Existing `markPublished()` syncs Excel rows only if `processing_status IN ('IMPORTED','PUBLISHED')`; new Auto-plan rows `AUTO_SCHEDULED` will not be updated until condition is amended and migration constraints are verified.
3. Phase 2C `publishQueuedJob` requires `social_accounts.access_token_encrypted` nonblank while LIVE token retrieval also uses `FacebookTokenStorage`; verify exact token source for your Page before enabling.
4. `socialhub.mock-queue` is synthetic-only and is not a 100-account end-to-end benchmark with real SocialHub tables. Test load in separate database/namespace.
5. No frontend source archive was included. `API_ROUTE_INVENTORY.csv` is a backend-only route list. FE wiring & CRUD gap analysis requires frontend sources.

## Build validation
The source archive declares Java 25, but the available build environment for preparing this patch does not provide Java 25/Maven. **Build and real Worker tests have NOT been run.**
