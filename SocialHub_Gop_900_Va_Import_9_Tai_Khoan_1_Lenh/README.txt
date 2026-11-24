SocialHub — Nhập 9 Fanpage x 100 bài bằng một lệnh (KHÔNG MOCK, KHÔNG LIVE PUBLISH).

File gộp 900 dòng chỉ để đối chiếu: KHÔNG tải file này lên UI Import, vì API nhận 1 socialAccountId cho cả file.
9 file xlsx trong folder excel_9 được import nối tiếp bằng 9 HTTP POST, mỗi POST gắn đúng 1 tài khoản.

PowerShell trong thư mục giải nén:
  .\Import-SocialHub-9-Fanpages.ps1             # Xem kế hoạch, KHÔNG ghi DB
  .\Import-SocialHub-9-Fanpages.ps1 -Import     # Nhập 900 DRAFT (xác nhận IMPORT-900)

Chọn Workspace có tên chính xác SocialHub Auto Test, API mặc định http://localhost:8081.
Script hỏi email/password SocialHub trực tiếp, không lưu mật khẩu/token; ghi checkpoint import_900_checkpoint.csv sau từng thành công.
Giữ PUBLISHER_ENABLED=false và THREADS_PUBLISHER_ENABLED=false, autoPublish=false.
Dừng và kiểm tra khi một import lỗi hoặc số lượng không phải 100/100.

LƯU Ý DỮ LIỆU: file 01 đã import 100 bài mang mã A04. Các file 02..10 có mã A05,A02,A03,A01,A07,A09,A06,A08,A10 (giữ nguyên mã nguồn, không tự đổi).
Dữ liệu chứa TEXT kiểm thử; xem xét nội dung trước khi bật LIVE; script KHÔNG tự phê duyệt, lên lịch hay đăng bài.
