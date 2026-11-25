# SocialHub Facebook Group Media Pilot v12 — Ảnh + Video MP4 / Reels tùy giao diện

Đây là **bản source và thử nghiệm có kiểm soát**, không phải xác nhận LIVE đã chạy trên Facebook. Nâng cấp dựa trên ZIP Edge v10 mà người dùng đã gửi và gói v11 đã tạo trước đó.

## Chức năng
- TEXT: giữ luồng CDP keyboard đã chạy thử thành công.
- IMAGE: giữ chọn tối đa 10 ảnh JPEG/PNG/WebP bằng CDP; bắt buộc xác nhận thumbnail đầy đủ.
- VIDEO: nhận **đúng một** `post_media.media_type` `VIDEO`/`REEL`, tải MP4 về máy qua **host HTTPS được cho phép**, theo luồng từng MB (tối đa 256 MiB), xác minh magic `ftyp`, rồi đưa vào đúng một input video trong Group Composer bằng CDP. Sau đó người dùng phải tự xác nhận video đã được Facebook xử lý xong và đúng nội dung trước LIVE. **Không có tự Retry và không đăng tự động.**
- REEL: Meta đã chuyển dần video thành Reels nhưng không phải mọi Facebook Group/thiết bị có UI tạo Reel riêng. Pilot hiện chỉ đính kèm MP4 vào **Group Post Composer**. Nó **không ép bài đăng thành Reel và không tự nhận là Reel**. Nếu Group không hiển thị đúng tùy chọn, dừng để khảo sát UI thật.
- Preview PENDING không claim, không thay đổi trạng thái. Claim chỉ khi **operator bấm LIVE**, Task là `READY`, Post `APPROVED`, Group đúng, đã đến giờ, media không thay đổi.
- Nếu Facebook đã được bấm Đăng nhưng chưa xác minh được permalink, Task phải ở `NEEDS_REVIEW`, **không Retry**.
- Không sửa database, Fanpage, Threads, Google Sheets, lịch 340 Task ảnh; không tạo thêm 659 video Tasks.

## Các endpoint
- Giữ nguyên `GET /internal/group-worker/preview` và `GET /internal/group-worker/image-manifest` v11 (dùng để đọc tất cả `post_media`).
- Giữ `/image-claim` v11 cho Task ảnh, và `/claim` nguyên bản cho Task văn bản.
- Thêm `POST /internal/group-worker/video-claim`, atomic claim một Task video duy nhất; không có API tự đăng.
- Bridge thêm `GET /inspect-media`, `POST /stage-video`; v11 `/inspect-images`, `/stage-images` vẫn được hỗ trợ.

## Cài (không chạy ngay khi bạn chưa sẵn sàng)
Giải nén ZIP; trong thư mục chứa `INSTALL-MEDIA-PILOT.ps1` mở PowerShell:

```powershell
$backend = 'D:\vox-social-media\SocialHub_Automation_2K_Phase1_SafePatch\SocialHub-Backend-Threads-AutoPublishing-v1\SocialHub-Backend'
$pilot = Join-Path $backend 'SocialHub-Edge-Group-Pilot\SocialHub-Edge-Group-Pilot'

.\INSTALL-MEDIA-PILOT.ps1 -BackendPath $backend -PilotPath $pilot         # Chỉ kiểm tra
.\INSTALL-MEDIA-PILOT.ps1 -BackendPath $backend -PilotPath $pilot -Apply  # Cài, tự backup
```

Nếu có source chỉnh sửa trên máy khác với v10/v11 đã kiểm tra hash, installer sẽ dừng. Đừng ép ghi đè.

Sau khi cài: build/restart Backend vào thời điểm Fanpage/Threads không có job LIVE, reload extension ở `edge://extensions`, refresh tab Group, dừng Bridge cũ chiếm cổng 8765 rồi dùng:

```powershell
& "$pilot\Start-EdgeBridge-Media.ps1" -TaskId '<UUID 1 Task PENDING đúng Group>'
```

**Phải đặt `GROUP_WORKER_TARGET_TASK_ID` và `GROUP_WORKER_EXPECTED_GROUP_ID` trùng giữa IntelliJ Backend và Bridge.** Token nhập riêng, không dán vào chat.

## Test an toàn trên UI
1. Chọn 1 Group Task `PENDING` trong SocialHub, giữ nguyên chưa duyệt và chưa đến giờ.
2. Bấm **0. Kiểm tra ảnh/video/lịch PENDING** trong Edge v12 để xem đúng media loại IMAGE/VIDEO, số file và hostname CDN. Đây là READ-ONLY.
3. Khi và chỉ khi muốn test LIVE sau này: dùng **một Task thử riêng** đã duyệt `READY`, đúng hạn. Bấm Preview → nạp nội dung → đính kèm ảnh hoặc video → nhìn thấy toàn bộ thumbnail/player hoàn thành → xác nhận thủ công → chỉ một lần LIVE.
4. Không bật 340 Task ảnh và 659 video ở chế độ tự đăng. Để làm hàng loạt, cần hoàn thiện worker upload/verify bền vững và lịch Task video riêng.

## Kiểm thử cục bộ

```powershell
py -3 -m unittest discover -s tests -p test_media_bridge.py -v
```

Java Controller mới đã kiểm tra `javac 21` với stub của phụ thuộc, chưa build Spring Boot project thật. `node --check` cả ba file JS và unit Bridge đã chạy. **Chưa kiểm chứng trên Edge/Facebook thật** nên không thể đảm bảo Facebook cho upload hoặc tạo Reel trong Group. Nếu UI Facebook thay đổi, pilot sẽ từ chối thay vì đăng thiếu media.
