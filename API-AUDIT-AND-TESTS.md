# SocialHub — rà soát API trên source ZIP Cookie-Ready(1)

## Phạm vi
Đã đọc 15 controller, các service cốt lõi (Authentication, Workspace, WorkspaceMember, SocialAccount, FacebookGroup, Post, PublishingJob) và lớp bọc response. Không thay đổi database/Flyway, worker, JWT, Cookie, CSRF, Excel Import hay quy trình đăng bài.

## Đã sửa trong bản này
1. Bổ sung `GET /api/workspaces/{id}` ở Controller, Service interface và implementation; kiểm tra quyền truy cập qua `AccessService.workspace(id)`.
2. `POST /api/workspaces` trả HTTP 201 thay vì 200; response JSON vẫn được `ApiResponseAdvice` bọc theo status thực tế.
3. `POST /api/workspaces/{workspaceId}/members` trả HTTP 409 với thông báo rõ ràng khi thêm người đã là thành viên, thay vì đợi lỗi unique constraint từ DB.

## API đã có trong source (giữ nguyên)
- Auth: csrf, register, login, me, logout.
- Workspace: create, list, rename, delete; thêm detail trong bản này.
- Members: list, add, remove.
- Social accounts: create, list, delete.
- Facebook groups: create, list, delete.
- Post: create, list (page/status/search), detail, update, delete.
- Post media: list, create, delete.
- Excel imports: import Excel, list, detail.
- Publishing jobs: create/list by post, cancel, confirm published/not published, retry, reviews.
- Publishing attempts: list by job.
- Group publishing tasks: list, create, ready, confirm, cancel; internal group worker: claim/result.
- Campaign: create, list, detail, add job, pause, resume, progress.

## Chưa được chứng minh bằng runtime test — không khẳng định đã hoàn tất
- `ApiResponseAdvice` bọc các response JSON legacy ở runtime, nhưng Swagger OpenAPI schema của những Controller trả DTO/List trực tiếp có thể **không phản ánh wrapper**. Frontend nên dùng response JSON thực tế hoặc chuẩn hóa DTO contract riêng.
- Swagger UI với CSRF cần token `X-XSRF-TOKEN` cho các thao tác POST/PUT/DELETE; source đã có ô header thủ công trên login, nhưng không phải mọi API đều có ô này. Khi test các thao tác ghi, cần đảm bảo header + cookie cùng tồn tại.
- DELETE Workspace có thể tác động đến dữ liệu liên quan qua cascade ở database. **Không test xóa Workspace đang chứa dữ liệu thật**.
- Facebook Group worker và Meta publishing chưa được chứng minh đăng thật; kết quả mock không thay thế kiểm thử Meta thực tế.
- Không chạy Maven/Java 25 trong môi trường tạo ZIP này; yêu cầu `mvn clean compile` và kiểm thử trên máy người dùng.

## Smoke test một lượt trước khi làm Frontend
1. `mvn clean compile` bằng Java 25; chạy app với env đã cấu hình.
2. `GET /api/auth/csrf`, login với `X-XSRF-TOKEN`, `GET /api/auth/me` (200).
3. `GET /api/workspaces` (200); `GET /api/workspaces/{id}` với workspace của mình (200).
4. `GET /api/workspaces/{id}/posts?page=0&size=5`, thử `status=SCHEDULED` và `search=MOCK` (200).
5. `GET /api/workspaces/{id}/social-accounts`, `/groups`, `/excel-imports` (200).
6. Tạo workspace thử và thêm thành viên thử; thêm trùng trả 409. Chỉ xóa workspace thử **rỗng** nếu đã xác minh không có dữ liệu phụ thuộc.
7. Logout có CSRF header; `/api/auth/me` sau logout trả 401.

## Lưu ý bảo mật
Không đóng gói `.env`, `.idea`, `target` hay token thật. `XSRF-TOKEN` không HttpOnly để client gửi CSRF header; JWT auth cookie phải HttpOnly. Logout xóa cookie, chưa có cơ chế revoke JWT server-side.
