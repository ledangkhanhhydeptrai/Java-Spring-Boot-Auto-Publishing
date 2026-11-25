# SocialHub Chrome Group Composer — Diagnostic Fix v2

Bản sửa chỉ thay `content.js` của tiện ích **SocialHub — Facebook Group One-Task Pilot** đang được Google Chrome nạp, không sửa Backend Java, Python Bridge, database hoặc các Jobs Threads/Fanpage.

Lỗi hiện tại: API Preview trả đúng Task nhưng cửa sổ "Tạo bài viết" mở ra mà không điền văn bản. Bản sửa thử nhận diện tiêu đề Facebook ngay cả khi phần tử là `div` (không phải `span`/heading), đồng thời tìm hộp soạn bài thông qua placeholder thật và kiểm tra nút Đăng trong chính hộp thoại. Nếu không tìm thấy, hiển thị mã `COMPOSER-DIAG-v2` cùng số lượng phần tử được phát hiện, **không đăng bài**.

## Cài đặt

1. Giải nén ZIP; mở PowerShell trong thư mục có `INSTALL-CHROME.ps1`.
2. Chạy `./INSTALL-CHROME.ps1`. Script đọc đường dẫn các tiện ích unpacked trong Chrome Profiles (`Preferences`) và chỉ thay file khi tìm thấy **duy nhất một** thư mục có manifest SocialHub Group Pilot, với SHA-256 thuộc các phiên bản đã xác nhận. Script sao lưu `content.js` trước khi thay. **Không đọc mật khẩu, cookie hoặc token**.
3. Nếu không tìm ra đúng thư mục hoặc tìm thấy nhiều bản: vào `chrome://extensions` > **SocialHub — Facebook Group One-Task Pilot** > **Details** > lấy đường dẫn **Loaded from**, sau đó chạy `./INSTALL-CHROME.ps1 -ExtensionPath 'ĐƯỜNG_DẪN_EXTENSION'`. Không chọn ngẫu nhiên.
4. Trong Chrome: `chrome://extensions` > **Reload** SocialHub Pilot. Quay lại tab Group ID `1326570106277759`, đóng cửa sổ tạo bài cũ, nhấn Ctrl+F5.
5. Mở tiện ích, chọn **1. Xem trước Task** rồi **2. Nạp bản nháp**. Đợi kết quả cuối. **Không bấm 3. Đăng LIVE** khi chưa thấy đầy đủ nội dung.

Nếu vẫn không được, **gửi nguyên dòng lỗi bắt đầu `COMPOSER-DIAG-v2`**: thông báo chỉ gồm các con số, không chứa nội dung trang Facebook hay thông tin đăng nhập. Nhờ đó có thể sửa dựa trên DOM thực tế thay vì đoán tiếp.

Đã chạy `node --check` và bốn mô phỏng cơ bản (modal role=dialog; modal không có role; tiêu đề là div; editor tạo muộn). Chưa chạy thử trực tiếp trên giao diện Facebook của người dùng.
