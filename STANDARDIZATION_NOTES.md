# SocialHub Backend — standardization pass 1

- Renamed project ZIP and removed `.env`, IDE workspace data, build output, and Python caches from the distribution.
- Added `com.socialhub.response.ApiResponse<T>` with `status`, `message`, and `data`.
- Centralized JWT expiry, database and Meta settings via environment placeholders; JWT secret has no embedded production fallback.
- Expanded `.env.example` with safe placeholder values.
- Existing controllers intentionally retain their previous response formats. **ApiResponse is not yet wired into controllers**; doing so requires an explicit API contract migration and regression testing.
- Existing import/worker logic is unchanged. No compile/test execution has been performed on this repackaged project.
- Do not enable real publishing until the integration tests and security review pass.
- Spring Boot does not load `.env` automatically. Supply variables via your IDE run configuration, shell, Docker Compose or a deliberate env-loader.
- Rotate any real credentials that were included in the original archive.
