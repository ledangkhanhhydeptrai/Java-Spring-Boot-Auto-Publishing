# SocialHub Cookie Authentication — tests and limitations

## Changes
- Login/register set `SOCIALHUB_AUTH` as HttpOnly cookie; responses contain Profile, not JWT.
- `/api/auth/me` authenticates using the cookie; Bearer Authorization is no longer accepted.
- `/api/auth/logout` clears cookie, but **does not revoke an already copied JWT**. Full server-side revocation / rotating refresh tokens are not implemented.
- `/api/auth/csrf` returns a CSRF token and sets readable `XSRF-TOKEN` cookie. This is an anti-CSRF token, **not** an authentication token. Every POST/PUT/PATCH/DELETE browser request needs `X-XSRF-TOKEN` header.
- Cookie attributes: HttpOnly, SameSite=Lax, Secure=false for localhost HTTP. Set Secure=true with HTTPS in production. Avoid mixing `localhost` and `127.0.0.1`.
- CORS allows credentials from `CORS_ORIGIN` (default http://localhost:5173).
- No Flyway/database changes, Post pagination and publishing modules unchanged.

## Browser flow
1. `GET http://localhost:8080/api/auth/csrf` with credentials; response `data` is the CSRF token.
2. `POST /api/auth/login` with JSON credentials, `credentials: include`, and `X-XSRF-TOKEN` set to step 1 token.
3. `GET /api/auth/me` with credentials -> 200 Profile.
4. For every mutation, send `X-XSRF-TOKEN` from latest `XSRF-TOKEN` cookie or fetch `/api/auth/csrf` again after login/logout.
5. `POST /api/auth/logout` with CSRF header and credentials -> cookie expired.
6. `GET /api/auth/me` -> 401.

Example browser fetch:
```js
const base = 'http://localhost:8080';
const csrfResponse = await fetch(`${base}/api/auth/csrf`, { credentials: 'include' });
const csrf = (await csrfResponse.json()).data;
const login = await fetch(`${base}/api/auth/login`, {
  method: 'POST', credentials: 'include',
  headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
  body: JSON.stringify({ email: 'YOUR_EMAIL', password: 'YOUR_PASSWORD' })
});
```

## Caveats
- **Not verified by Maven** in this build environment (Java 25/Maven unavailable). Compile and test locally.
- Swagger UI may not automatically attach CSRF headers to POST endpoints; use browser fetch, curl with cookie jar, or configure Swagger UI CSRF separately.
- CSRF cookie is readable by JavaScript by design; the auth cookie is not.
- The `/internal/group-worker/**` CSRF exemption preserves the existing worker endpoint; ensure worker token authentication is enforced before deploying publicly.
- No refresh token or server-side JWT revocation yet. Do not describe logout as server-side invalidation.
