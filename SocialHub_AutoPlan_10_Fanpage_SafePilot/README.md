# SocialHub: Prepare scheduled LIVE Jobs for 10 Fanpages

This wrapper uses ONLY the existing SocialHub API `POST /api/workspaces/{workspaceId}/excel-imports/{importId}/auto-plan/one` which is already implemented in the Backend project. No Java modification, no SQL UPDATE, no direct Meta requests, no MOCK.

It automatically discovers the 10 import IDs by their file names in the currently authenticated `SocialHub Auto Test` workspace and validates that all 10 accounts are connected Facebook Pages. Source rows start at Excel row 2.

PowerShell from the extracted directory:

```
.\Schedule-SocialHub-10-Fanpages.ps1
```

Read-only preview defaults to 1 row per page; tomorrow at 09:00 VN, spread across pages at 5-minute offsets. **Before creating pending LIVE jobs, verify content, the test database connection, and that Facebook / Threads publishers are disabled.** When approved and authorized, to create only 1 pilot Job per Page (10 total):

```
.\Schedule-SocialHub-10-Fanpages.ps1 -Commit -ReviewedContent
```

The script will require a separate phrase `APPROVE-AND-SCHEDULE-10`. It creates PENDING Jobs and auto-approves the selected imported Posts via the existing Backend API; it does not publish now or enable Facebook Bulk Run. Always verify the pilot first.

To preview the complete 100 rows per page with two slots per day per page, spread over 50 days:

```
.\Schedule-SocialHub-10-Fanpages.ps1 -RowsPerFanpage 100
```

Only after reviewing and authorizing ALL content, create the remaining planned Jobs:

```
.\Schedule-SocialHub-10-Fanpages.ps1 -RowsPerFanpage 100 -Commit -ReviewedContent
```

This full confirmation would create pending Jobs for 1,000 posts, NOT directly publish them. Existing posts already scheduled during the pilot are detected by the Backend and not duplicated. The local `schedule_results.csv` stores the responses after every row. If a network error occurs, stop and inspect the actual DB before rerunning.

**Important limitation:** `auto-plan/batch` refuses imported rows whose `source_publish_date` is NULL. `auto-plan/one` supports choosing an explicit new date for those rows. Jobs are still not attached to a Facebook Bulk Run; do not turn on Bulk LIVE Worker without verifying the intended promotion/claim pathway. The files imported are TEST content; do not run the full publish without reviewing them and ensuring permission/anti-spam compliance.
