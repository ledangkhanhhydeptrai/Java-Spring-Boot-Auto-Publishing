SOCIALHUB – REEL / VIDEO TỪ EXCEL, KHÔNG TẠO BÀI BẰNG TAY

Bộ dữ liệu: 660 dòng, 10 tài khoản, 330 VIDEO và 330 REEL.

1. Excel: SocialHub_REEL_VIDEO_660_Excel.xlsx
   - AUTO_MEDIA_660 chứa mã bài, tài khoản, mediaType và URL MP4.
   - FORM_MAU giữ nguyên tên cột của mẫu cũ, không copy dữ liệu mẫu.
   - PILOT_2 có 1 REEL + 1 VIDEO để kiểm tra an toàn trước.
   - KIEM_TRA: 33 REEL + 33 VIDEO cho mỗi tài khoản.
2. CSV UTF-8 để PowerShell chạy tự động (xuất từ chính Excel):
   - PILOT_2.csv
   - AUTO_MEDIA_660.csv
3. Trong thư mục giải nén, mở PowerShell và chạy:
   Set-ExecutionPolicy -Scope Process Bypass
   .\Run-SocialHub-Excel-Media.ps1 -CsvFile .\PILOT_2.csv -Type REEL -MaxRows 1

   Script hỏi tài khoản/mật khẩu SocialHub, kiểm tra Page mapping qua API,
   tạo Post DRAFT rồi tạo PostMedia kiểu REEL đúng với link MP4.
   Không phải vào UI để tạo từng bài.

4. Để STAGE đầy đủ 660 bài (KHÔNG đăng công khai), dùng:
   .\Run-SocialHub-Excel-Media.ps1 -CsvFile .\AUTO_MEDIA_660.csv -Type BOTH -MaxRows 660

5. Chỉ khi đã xác minh Page và cấu hình SINGLE LIVE đúng, có thể thử
   -PrepareJob trên một REEL, với -ExpectedPageId phải bằng đúng Page đích.
   Ví dụ cần thay 884633188075736 bằng ID Page THẬT đã xác minh:
   .\Run-SocialHub-Excel-Media.ps1 -CsvFile .\PILOT_2.csv -Type REEL -MaxRows 1 -PrepareJob -ExpectedPageId 884633188075736 -MinutesFromNow 30
   Script yêu cầu gõ PREPARE-LIVE mới tạo Job thật.
   Tạo Job không đồng nghĩa với Worker đã xử lý LIVE.
   Phải tắt Bulk mode và cấu hình FACEBOOK_LIVE_JOB_ID chính xác rồi
   khởi động lại Backend để Worker SINGLE LIVE có thể lấy đúng Job.

CẢNH BÁO QUAN TRỌNG:
- Backend hiện tại luôn lưu media từ cột Ảnh 1/Ảnh 2 của import XLSX thành IMAGE.
  KHÔNG upload trực tiếp FORM_MAU vào Excel Import để đăng REEL/VIDEO.
- FanpageQueueProcessor.claimBulk chỉ nhận media IMAGE. REEL/VIDEO bị chặn trong Bulk.
- Khi người dùng gửi cấu hình: app.facebook.video-publishing-enabled=false,
  do đó VIDEO LIVE chưa được mở để xuất bản.
- Trong hình kết quả bài IMAGE gần đây, external_post_id mở đầu 1026968390505000,
  trong khi danh sách tài khoản đặt cho TK01 có Page ID 884633188075736.
  CẦN đối chiếu mapping thực trước khi bật LIVE hoặc chạy nhiều tài khoản.
- Các URL MP4 tái sử dụng lặp lại. Đừng đăng hàng trăm bài trùng lặp trên nhiều Page.
- Checkpoint file chống đăng ký lại bài sau khi chạy thành công một phần.
  Nếu có lỗi tạo Post nhưng chưa ghi checkpoint, kiểm tra Backend trước khi chạy lại.
- Người dùng tự thực thi script trên máy họ; ChatGPT không kết nối trực tiếp tới localhost.
