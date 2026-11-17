# SocialHub Threads OAuth integration

## Added routes

- `GET /api/threads/oauth/start?workspaceId=<UUID>` — requires logged-in OWNER/ADMIN. Start from `http://localhost:8080` in the browser where SocialHub JWT cookie exists.
- `GET /api/threads/oauth/bridge?state=...` — public HTTPS ngrok handoff; validates signed, expiring state and sets an HttpOnly/Secure cookie on the ngrok domain.
- `GET /api/threads/oauth/callback?code=...&state=...` — public HTTPS Meta callback; validates state, retrieves token/profile, saves AES-GCM encrypted token in `social_accounts`, then redirects to frontend.

## Meta developer dashboard

In Threads API settings, set the **exact** OAuth redirect URI to:

`https://YOUR-NGROK-DOMAIN.ngrok-free.dev/api/threads/oauth/callback`

Add a Threads test user and grant `threads_basic`, `threads_content_publish` access as applicable. This integration does not implement posting to Threads yet; it connects the account and securely stores the token.

## Environment variables (backend)

```powershell
$env:THREADS_APP_ID="YOUR_THREADS_APP_ID"
$env:THREADS_APP_SECRET="YOUR_THREADS_APP_SECRET"
$env:THREADS_REDIRECT_URI="https://YOUR-NGROK-DOMAIN.ngrok-free.dev/api/threads/oauth/callback"
$env:THREADS_FRONTEND_RETURN_URL="http://localhost:5173/social-accounts"
$env:THREADS_OAUTH_STATE_SECRET="A_RANDOM_SECRET_AT_LEAST_32_CHARACTERS"
# Generate a 32-byte AES key once; persist it securely, never regenerate while encrypted tokens are in DB.
$key = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($key)
$env:THREADS_TOKEN_ENCRYPTION_KEY=[Convert]::ToBase64String($key)
```

**Security:** Never commit real secrets. Keep ngrok + Spring Boot running. `THREADS_TOKEN_ENCRYPTION_KEY` must be stable; losing it makes stored tokens unreadable. The state secret should be a random 32+ character value. Never put the Meta App Secret in the frontend.

## Test

1. Run backend on `http://localhost:8080` and ngrok tunnel `ngrok http 8080`.
2. Log into SocialHub frontend at `http://localhost:5173`.
3. In that same browser, open `http://localhost:8080/api/threads/oauth/start?workspaceId=YOUR_WORKSPACE_UUID`.
4. The browser redirects through the ngrok bridge to Threads authorization and back to ngrok callback.
5. After consenting, check `http://localhost:5173/social-accounts?threads=connected` and `GET /api/workspaces/{workspaceId}/social-accounts`. A THREADS account should have `CONNECTED` status.

`/callback` opened directly **without** Meta's `code` and valid state is not a valid login test and will redirect with an error. `401` on `/start` means no local JWT auth cookie; log in first. Ensure the exact redirect URI matches in both settings. Do not use a different ngrok domain halfway through the flow.

## Important limitations

- Account connection is implemented, but publishing jobs still require a Threads publisher adapter. The existing Facebook publisher is unchanged.
- Live end-to-end OAuth has not been tested here; requires your Meta app credentials, Threads test user and running ngrok.
