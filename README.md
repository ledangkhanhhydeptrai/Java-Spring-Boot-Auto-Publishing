# SocialHub — Facebook Group one-task controlled autopublish pilot

**Scope:** The user-provided SocialHub backend (Nov 24 source zip) + Frontend Group Publishing Tasks.
**This is a source-code patch, not a tested public Facebook publish.** No real Facebook/Backend credentials or group access were available in the patch environment.

## Specific defects fixed

1. Frontend `PENDING → READY` conflicted with worker claiming `PENDING`; now the worker claims **READY only**.
2. Worker requires the specific `GROUP_WORKER_TARGET_TASK_ID` **and** numeric `GROUP_WORKER_EXPECTED_GROUP_ID`; otherwise it claims nothing.
3. Worker requires post status READY/SCHEDULED and `approval_status='APPROVED'`; task scheduled time must be due.
4. Frontend adds `PROCESSING`, `NEEDS_REVIEW`, `FAILED`, and avoids offering cancel/confirm while `PROCESSING`.
5. `/ready` rejects invalid transitions and manual mutation of `PROCESSING` tasks.
6. `--watch` polls only the one task until due, with a maximum wait; **never retries** an attempted publication automatically.
7. Wrong-group URL after claim marks `NEEDS_REVIEW` rather than leaving the task stuck in `PROCESSING`.
8. The frontend manual published confirmation requires a Group post permalink, not just a Facebook domain.

## IMPORTANT restrictions

- Do **not** install/restart Backend while existing Threads or Facebook Fanpage jobs are in progress. Applying source files does not switch the running JVM; restart only in a safe maintenance window.
- Never run this on groups you do not administer or without permission to publish.
- Not an official Facebook Groups Graph API integration. Playwright UI selectors may break after Facebook changes. Auto browser posting can carry Facebook account/platform-policy risks.
- Text and up to 5 IMAGE posts from HTTPS Cloudinary are the only supported media. VIDEO/REEL is rejected safely; `PUBLISHED` is only reported after locating the task marker **in the correct group feed with a permalink**. The marker `[SocialHub Group Pilot ...]` is appended to the **public post** for verification.
- Group Worker browser profile stores sensitive session data locally. Do not share/compress/commit it. No CAPTCHA, login/2FA or anti-bot bypass.
- No automatic retry on ambiguous publication. Verify `NEEDS_REVIEW` by checking Facebook before any other action.

## Install source files on Windows (once, in a maintenance window)

Extract this ZIP, then in the extracted folder run:

```powershell
.\INSTALL.ps1 -BackendPath "D:\vox-social-media\SocialHub_Automation_2K_Phase1_SafePatch\SocialHub-Backend-Threads-AutoPublishing-v1\SocialHub-Backend" -FrontendPath "D:\vox-social-media\SocialHub-FrontEnd-AutoPublishing"
```

The installer SHA256-checks **all target files before modifying any**, backs them up, and refuses unknown versions. If your local code changed, **do not force** the install; provide the newer files for reconciliation.

Build manually using the project's own Java 25 / Maven and the frontend's normal build command. **Do not restart running Backend until Threads & Fanpage publishing is idle.**

## Configure safe 1-group pilot

1. In SocialHub **Facebook Groups**, create/select the Group you administer. Its `externalGroupId` must be the numeric Facebook group ID and `groupUrl` must be `https://www.facebook.com/groups/<numeric ID>/`.
2. Select an APPROVED Post (status `READY`/`SCHEDULED`) whose content you approve. A text-only post is the easiest initial test.
3. In **Group Publishing Tasks**, create **one task** for this group, with `scheduledAt` now or a future time. **It starts PENDING**. Verify it, then press **Duyệt để Worker đăng** to move it to READY.
4. Copy the created **Task ID** displayed in the Group Task list (or API response). No other Group Tasks should be made READY for the pilot.
5. **Before Backend start**, set these environment variables in IntelliJ Run Configuration (or the environment inherited by Java):
   - `GROUP_WORKER_TOKEN`: strong random value shared with the Python worker
   - `GROUP_WORKER_TARGET_TASK_ID`: the exact Task UUID
   - `GROUP_WORKER_EXPECTED_GROUP_ID`: numeric Group ID
   
   The patched Backend resolves `app.group-worker-token` from `GROUP_WORKER_TOKEN` if a direct app property is absent. Do **not** copy tokens into source files or screenshots. A wrong/missing ID causes `/claim` to return 204 (no action).
6. Start/restart Backend **only after** Threads and Fanpage publishers are idle. Verify the existing publishers retain their settings.

Install the browser worker (one-time):

```powershell
cd "D:\vox-social-media\SocialHub_Automation_2K_Phase1_SafePatch\SocialHub-Backend-Threads-AutoPublishing-v1\SocialHub-Backend\group-worker"
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m playwright install chromium
```

**First configure Facebook browser profile without publishing:**

```powershell
cd "<EXTRACTED_PATCH_DIR>"
.\Start-GroupPilot.ps1 -BackendPath "<BACKEND_DIR>" -GroupId "<NUMERIC_GROUP_ID>" -PrepareProfile
```

Log into Facebook yourself in the visible Chromium window. Confirm you are an admin or authorized poster in the specified Group. This command creates no task and publishes nothing.

**Then publish exactly one task when scheduled:**

```powershell
# Token must be supplied in this terminal, and match Backend. Never hardcode in scripts.
$sec = Read-Host "Group Worker token" -AsSecureString
$env:GROUP_WORKER_TOKEN = [System.Net.NetworkCredential]::new('', $sec).Password
.\Start-GroupPilot.ps1 -BackendPath "<BACKEND_DIR>" -GroupId "<NUMERIC_GROUP_ID>" -TaskId "<GROUP_TASK_UUID>" -Watch -AllowLive -MaxWaitMinutes 120
```

The worker polls every 30 seconds for **that exact READY and due task**, claims it once, attempts one UI publish, verifies the permalink, then **exits**. `-AllowLive` is an explicit safeguard before a public posting. Without it the script refuses to publish.

## Verifying outcome

- `PUBLISHED`: group post was located on the correct Group feed and a real permalink recovered. Verify the actual Facebook link.
- `NEEDS_REVIEW`: no confirmed result (e.g. UI changed, permissions, group moderation, missing permalink), **do not blindly retry**.
- `READY`: approved, worker has not claimed it, or date still in future.
- `PENDING`: needs approval in SocialHub. Do not assume ready.

Backend/Fanpage publishing, Threads scheduling and Google Sheets are untouched by this patch. No DB migration, import or job recreation.
