# SocialHub Backend – API normalization (stage 1)

- `ApiResponse<T>` is the canonical `{status, message, data}` shape.
- `ApiResponseAdvice` automatically wraps **legacy JSON success responses** under `/api/**` (except internal group-worker endpoints). Existing PostController responses are already wrapped and are not double-wrapped.
- Existing HTTP 204 responses remain **bodyless**, preserving HTTP semantics. Auth/register/login response payloads are now inside `data`; adjust frontend parsing accordingly.
- The existing `ErrorHandler` emits `ApiResponse` for MVC exceptions. Authentication/authorization errors raised in Spring Security filters still need separate integration verification; this stage does not change security policy.
- OpenAPI schema for legacy controller methods may still show the original DTO, because wrapping occurs at response time. Explicit per-controller `ApiResponse<T>` signatures are recommended before freezing the frontend contract.
- Existing list endpoints other than Posts are **not yet paginated**. Do not load large lists unbounded in the frontend.
- `DB_PASSWORD` and `JWT_SECRET` are required environment variables. `.env.example` is a template; Spring Boot does not load `.env` automatically.
- `PUBLISHER_ENABLED=false` is recommended while testing. Do not run real publishing tests until reviewed.
- No database migrations or business logic were changed.

## Verification

1. Set `JWT_SECRET`, `DB_PASSWORD`, `DB_URL`, `DB_USERNAME`, `PUBLISHER_ENABLED=false` in IntelliJ Run Configuration.
2. Run `mvn clean compile` or use IntelliJ Maven `compile`. The uploaded ZIP does not include Maven Wrapper scripts.
3. Start Spring Boot; verify `/actuator/health` and Swagger.
4. Test authenticated GET `/api/workspaces/{workspaceId}/posts?page=0&size=20`: `status`, `message`, `data`.
5. Test GET `/api/workspaces`: list is under `data`.
6. Verify DELETE endpoints still return 204, Post DELETE returns 200 with ApiResponse.
7. Verify error responses, including unauthorized and forbidden, separately.

**Build note:** This archive has not been Maven-built in the packaging environment (Java 21 available here; project requests Java 25). Compile and test locally with Java 25.
