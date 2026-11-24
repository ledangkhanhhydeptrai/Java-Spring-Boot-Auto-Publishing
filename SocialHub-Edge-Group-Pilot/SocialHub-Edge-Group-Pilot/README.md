# SocialHub — Microsoft Edge Facebook Group one-task pilot (24/11/2026)

Bản tích hợp thử nghiệm giữa **SocialHub Backend** và **cửa sổ Microsoft Edge đang đăng nhập Facebook**, không khởi động Chromium Playwright riêng và **không copy cookie, mật khẩu, profile hoặc mã xác minh**.

## Phạm vi cố định

- Group ID **1326570106277759** (nhóm thật trên Facebook hiển thị **Khánh Hỷ**, dù nhãn nội bộ SocialHub là *SocialHub Automation Testing*).
- Task ID **15e8de3f-cd4b-4ffb-96d0-ae5ec42a6c49** (`READY` trên ảnh FE).
- Bản thử **chỉ bài TEXT, đã APPROVED, đến giờ**, chỉ 1 lần đăng thật sau khi người dùng nhấn xác nhận trong tiện ích Edge.
- Không hỗ trợ video, reel, ảnh, đa nhóm, headless, đăng không cần người xác nhận hay hẹn giờ tự kích hoạt trong bản thử này.
- Một mã xác nhận `[SocialHub Group Pilot 15e8de3f]` được thêm vào **nội dung bài đăng công khai** để kiểm tra đúng bài trong nhóm.
- Không thay đổi Threads, Fanpage, Google Sheets, Excel Imports hay DB migration.

## Cấu trúc

- `backend/GroupWorkerController.java`: áp dụng từ bản Group SafePilot, thêm **GET /internal/group-worker/preview** *chỉ đọc*, giữ điều kiện claim đúng task + group + `READY` + post `APPROVED` + đến giờ.
- `bridge/bridge.py`: HTTP bridge chạy **127.0.0.1:8765**, chỉ gọi `/preview`, `/claim`, `/{taskId}/result`; API token chỉ nằm ở terminal Python, mã ghép nối ngẫu nhiên đổi mỗi lần chạy.
- `extension/`: tiện ích Edge Manifest V3, chạy trong tab Facebook Group hiện tại. `Xem trước → Nạp bản nháp → Đăng 1 bài`. Không copy phiên đăng nhập.
- `INSTALL-BACKEND.ps1`: sao lưu và SHA256-kiểm tra bản Java trước khi ghi đè; **tự dừng nếu khác phiên bản**.
- `Start-EdgeBridge.ps1`: mở bridge không tự đăng bài.

## 1. Cài file Backend (khi hết lịch đăng Threads/Fanpage)

**Không restart Spring Boot khi vẫn đang xử lý các Job Threads/Fanpage.** Bản này thay một file Java trên đĩa; nó chỉ có tác dụng khi build và khởi động lại Backend an toàn.

Mở PowerShell ở thư mục vừa giải nén ZIP:

```powershell
.\INSTALL-BACKEND.ps1 -BackendPath "D:\vox-social-media\SocialHub_Automation_2K_Phase1_SafePatch\SocialHub-Backend-Threads-AutoPublishing-v1\SocialHub-Backend"
```

Script chỉ nhận SHA256 từ bản source Backend zip ngày 24/11 hoặc file controller trong Group SafePilot zip; nếu source của bạn đã sửa khác, script **không ghi đè**. Gửi file `GroupWorkerController.java` hiện tại để đối chiếu. Script tạo `*.backup_YYYYMMDD_HHmmss` trước khi thay. Không chạy lại `INSTALL.ps1` của patch cũ lên file mới.

Khi Threads/Fanpage không còn Job đang xử lý và bạn có thể restart, đặt **cùng một `GROUP_WORKER_TOKEN` mạnh** trong IntelliJ Run Configuration (không commit, không gửi vào chat), cùng các biến:

```text
GROUP_WORKER_TARGET_TASK_ID=15e8de3f-cd4b-4ffb-96d0-ae5ec42a6c49
GROUP_WORKER_EXPECTED_GROUP_ID=1326570106277759
GROUP_WORKER_TOKEN=<secret do bạn tự đặt>
```

*Không thay hoặc xóa các flag Threads và Fanpage đã hoạt động.* Trong thư mục Backend, build bằng Maven đang dùng: `mvn -DskipTests compile` hoặc `./mvnw.cmd -DskipTests compile` nếu dự án có Maven wrapper. Khởi động lại Backend trong cửa sổ bảo trì; kiểm tra không có lỗi Spring và luồng publisher vẫn giữ đúng cờ.

Nếu Java controller trước đây đã được thay bởi SafePilot, bản này chỉ bổ sung endpoint preview. Không cập nhật task sang `PUBLISHED` khi chưa có bằng chứng thực tế.

## 2. Cài tiện ích Edge (không cần restart Backend)

1. Mở `edge://extensions` trên **Edge** mà bạn đang đăng nhập Facebook.
2. Bật **Developer mode / Chế độ nhà phát triển**.
3. Chọn **Load unpacked / Tải tiện ích chưa đóng gói**, trỏ tới thư mục `extension` bên trong thư mục đã giải nén.
4. Mở `https://www.facebook.com/groups/1326570106277759/` bằng Edge đã đăng nhập, **F5 một lần** để nạp tiện ích.
5. Đóng cửa sổ tạo bài đang chứa chữ `Hello` nếu còn; không bấm nút Đăng thủ công.

Không cài tiện ích vào Edge của người khác. Tiện ích có thể truy cập DOM bài đăng trong trang Facebook Group — chỉ sử dụng trên máy bạn.

## 3. Khởi chạy Bridge (khi backend đã có endpoint preview)

Từ PowerShell **mới**, tại thư mục giải nén:

```powershell
.\Start-EdgeBridge.ps1
```

Script hỏi `GROUP_WORKER_TOKEN` mà bạn đã cấu hình trong IntelliJ (nhập qua trường ẩn, không gửi token trong chat). Bridge in ra **PAIRING CODE** — chỉ dán mã này vào popup tiện ích Edge. Sau khi đóng bridge, mã cũ mất hiệu lực.

**Không cần mở profile Playwright mới. Không dùng Arkose CAPTCHA bypass.** Nếu Facebook tự yêu cầu xác minh lại, dừng và hoàn tất xác minh bình thường bằng tài khoản của mình.

## 4. Đăng thử chính xác 1 Task

1. Đảm bảo bài `TEST Facebook Group – SocialHub Automation` trong SocialHub có **Post Approval Status = APPROVED**, Post Status = `READY`/`SCHEDULED`. Group Task ID phải `READY`. Nếu chưa `APPROVED`, phê duyệt bài trong SocialHub theo luồng duyệt bình thường; *không tự chỉnh DB*.
2. Trên tab Facebook Group đúng ID, bấm biểu tượng tiện ích Edge.
3. Dán PAIRING CODE, chọn **Kết nối Bridge**.
4. Chọn **1. Xem trước**. Nút này không claim/đổi trạng thái Job. Nếu báo thiếu API hoặc không được duyệt, **dừng**, không ép đăng.
5. Chọn **2. Nạp bản nháp**. Nội dung và marker được điền trong hộp tạo bài. **Kiểm tra trực quan nội dung đúng và quyền đăng trong nhóm**.
6. Chọn **3. Đăng đúng 1 bài (LIVE)** và bấm xác nhận. Bridge mới claim duy nhất task này thành `PROCESSING`; tiện ích bấm Đăng đúng một lần và tìm bài chứa marker cùng permalink thực tế.
7. Nếu có permalink khớp trong Group, Backend ghi `PUBLISHED`; nếu không chắc chắn, ghi `NEEDS_REVIEW`. **KHÔNG nhấn đăng lại**, mở nhóm để kiểm tra thủ công.

**Không đặt lịch tự động với extension này**. Bản thử yêu cầu một lần xác nhận công khai trong Edge. Sau khi được chứng minh trên Group thật, có thể mở rộng an toàn sang scheduler/lựa chọn nhóm mà vẫn cần tuân thủ quy định Facebook.

## Xử lý sự cố

- `Task is not READY`: task hiện không còn ở trạng thái đã duyệt chờ Worker.
- `Post is not approved`: duyệt Post trong SocialHub trước, không sửa bằng SQL.
- `Task is not due yet`: cần đợi lịch. Không tự thay đổi lịch đang chạy.
- `Backend HTTP 404 /preview`: Backend chưa cài/build/restart controller bản này.
- `Backend HTTP 401`: secret trong terminal bridge và IntelliJ khác nhau.
- `No exact Group Task returned`: backend chưa có `GROUP_WORKER_TARGET_TASK_ID` hoặc ID nhóm khớp.
- `Ô soạn thảo đã có nội dung khác`: đóng hoặc xóa bản nháp cũ trong Edge, rồi nạp lại.
- `Không xác định duy nhất nút Đăng`: giao diện Facebook thay đổi; không cố click bừa.
- `NEEDS_REVIEW`: có thể đã click Đăng nhưng không lấy được permalink. Kiểm tra bài thực tế trước mọi thao tác khác.
- `PROCESSING` còn treo (ví dụ tắt Edge giữa chừng): **không chạy lại**; cần kiểm tra log và Facebook để hòa giải trạng thái.

## Test/offline verification

Bộ này đã chạy test bridge mock (không gọi Facebook), `python -m unittest discover -s tests -v`, và `node --check` cho JS. **Chưa build Backend Java trong môi trường của bạn, chưa thử LIVE trên Facebook và chưa xác nhận DOM Edge sau khi Facebook cập nhật**. Nếu `INSTALL-BACKEND.ps1` từ chối vì checksum không khớp, đó là cơ chế an toàn; không dùng `-Force`.
