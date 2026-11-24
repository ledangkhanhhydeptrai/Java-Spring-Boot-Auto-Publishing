# Edge Bridge 403 pairing fix — one-file patch

Scope: fixes local `SocialHub-Edge-Group-Pilot/bridge/bridge.py` only. No Backend Java, Frontend, Edge extension, Jobs, or Facebook activity is changed.

## Why

Previous bridge authorization required both an `Origin: chrome-extension://<32-char ID>` header **and** the correct `X-SocialHub-Pair` secret. Some browser extension service-worker requests omit `Origin` even when sent with host permission; those legitimate requests got HTTP 403.

Fix: explicit non-extension Origin remains **rejected**. A request with no Origin can proceed **only with the exact one-time pairing secret**. This is not a CAPTCHA or account-login bypass; Facebook access still requires an authenticated Edge session. Any rejection now logs the non-sensitive diagnostic `origin=...` and `pair_match=true/false` (never prints the secret).

## Installation (PowerShell)

1. Extract this ZIP. In that extracted patch directory run:

```powershell
.\INSTALL-BRIDGE.ps1 -PilotPath 'D:\vox-social-media\SocialHub_Automation_2K_Phase1_SafePatch\SocialHub-Backend-Threads-AutoPublishing-v1\SocialHub-Backend\SocialHub-Edge-Group-Pilot'
```

2. Installer validates original SHA-256, saves a `.bak-...` and changes only `bridge/bridge.py`; refuses unknown versions.
3. In the terminal **running Edge Bridge**, press `Ctrl+C`, then launch `Start-EdgeBridge.ps1` again. This rotates to a **new pairing code**; don't share it or `GROUP_WORKER_TOKEN` in chat/screenshots.
4. In Edge popup, use the NEW code, click **Kết nối Bridge**, then **1. Xem trước Task**.
5. Do **not** click **Đăng đúng 1 bài (LIVE)** until read-only preview confirms the exact Group, task, content, and approval.

If preview still returns 403, check Bridge terminal for `Bridge auth denied: origin=... pair_match=...`, sharing only that line (no secrets). If preview returns 409, it's an upstream readiness issue, not pairing.

## Notes

The `GET /favicon.ico 403` line is unrelated to the task and can occur when a browser tries to load the bridge URL manually. Testing a bridge by opening the URL in a normal browser tab is not sufficient, because only the extension has the pairing secret.
