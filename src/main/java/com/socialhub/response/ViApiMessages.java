package com.socialhub.response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Localizes display text at the API boundary; never changes domain states or persisted errors. */
public final class ViApiMessages {
    private static final String RESOURCE = "/i18n/api-messages-vi.tsv";
    private static final Pattern VI = Pattern.compile("[ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_:-]{1,120}");
    private static final Pattern ROW = Pattern.compile("(?i)^(?:sheet row|row|dòng)\\s+(\\d{1,8})\\s*:\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern HTTP = Pattern.compile("(?i)\\bHTTP\\s+(\\d{3})\\b");
    private static final Pattern META_CODE = Pattern.compile("(?i)\\b(?:error code|code)\\s*[=:]\\s*(\\d{1,10})\\b");
    private record Rule(String source, String translation) { }
    private record Catalog(Map<String, String> exact, List<Rule> prefix, List<Rule> errorPrefix) { }
    private static final Catalog CATALOG = load();

    private ViApiMessages() { }

    private static Catalog load() {
        Map<String, String> exact = new HashMap<>();
        List<Rule> prefix = new ArrayList<>();
        List<Rule> errorPrefix = new ArrayList<>();
        try (InputStream in = ViApiMessages.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing Vietnamese API message catalog: " + RESOURCE);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] parts = line.split("\\t", 3);
                    if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank())
                        throw new IllegalStateException("Invalid Vietnamese message catalog entry");
                    if (exact.put(parts[1].strip(), parts[2]) != null)
                        throw new IllegalStateException("Duplicate Vietnamese message catalog entry: " + parts[1]);
                    if ("P".equals(parts[0])) prefix.add(new Rule(parts[1], parts[2]));
                    else if ("E".equals(parts[0])) errorPrefix.add(new Rule(parts[1], parts[2]));
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read Vietnamese API message catalog", ex);
        }
        Comparator<Rule> longest = Comparator.comparingInt((Rule r) -> r.source().length()).reversed();
        prefix.sort(longest); errorPrefix.sort(longest);
        return new Catalog(Map.copyOf(exact), List.copyOf(prefix), List.copyOf(errorPrefix));
    }

    /** Top-level API messages: never expose a raw, unknown exception to the user. */
    public static String response(int status, String source) {
        if (source != null && !source.isBlank()) {
            String result = known(source, true);
            if (result != null) return result;
            if (VI.matcher(source).find() && !source.startsWith("Chi tiết kỹ thuật:")) return source;
        }
        return switch (status) {
            case 200, 201, 202, 204 -> "Thực hiện thành công.";
            case 400, 422 -> "Thông tin gửi lên chưa hợp lệ. Vui lòng kiểm tra lại.";
            case 401 -> "Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng đăng nhập lại.";
            case 403 -> "Bạn không có quyền thực hiện thao tác này.";
            case 404 -> "Không tìm thấy dữ liệu yêu cầu. Vui lòng kiểm tra lại lựa chọn.";
            case 409 -> "Thao tác xung đột với dữ liệu hoặc trạng thái hiện tại. Vui lòng tải lại và kiểm tra trước khi tiếp tục.";
            case 429 -> "Đã vượt giới hạn yêu cầu. Vui lòng thử lại sau theo hướng dẫn của hệ thống.";
            case 502, 503, 504 -> "Dịch vụ tạm thời không khả dụng. Vui lòng thử lại sau hoặc liên hệ quản trị viên.";
            default -> status >= 200 && status < 300 ? "Thực hiện thành công." : "Hệ thống chưa thể xử lý yêu cầu. Vui lòng liên hệ quản trị viên nếu lỗi tiếp diễn.";
        };
    }

    /** System-generated DTO explanations only. Unknown provider diagnostics stay available. */
    public static String detail(String source) {
        if (source == null || source.isBlank()) return source;
        String translated = known(source, false);
        if (translated != null) return translated;
        if (VI.matcher(source).find() || CODE.matcher(source.strip()).matches()) return source;
        // Preserve unknown diagnostics instead of inventing their meaning.
        return "Chi tiết kỹ thuật: " + source;
    }

    private static String known(String source, boolean errorContext) {
        String input = source.strip();
        if (input.startsWith("Single status G (any platform); Threads H; Facebook I")) {
            String result = "Cột G là trạng thái chung, H là đường dẫn Threads, I là đường dẫn Facebook.";
            if (input.contains("; overdue date F -> ")) {
                var date = Pattern.compile("overdue date F -> ([0-9/\\-]+)").matcher(input);
                result += date.find() ? " Ngày quá hạn ở cột F được cập nhật thành " + date.group(1) + "." : " Ngày quá hạn ở cột F được cập nhật.";
            }
            if (input.contains("Facebook external ID cannot form a verified URL")) result += " Chưa có đường dẫn Facebook xác minh được; không ghi trạng thái Facebook.";
            if (input.contains("Threads ID has no verified permalink")) result += " Chưa có đường dẫn Threads xác minh được; giữ nguyên cột H.";
            return result;
        }
        if (input.contains("; date from current synced Sheet (not historical original); ")) {
            String main = input.substring(0, input.indexOf("; date from current synced Sheet"));
            String translated = known(main, errorContext);
            if (translated != null) return translated + " Ngày lấy từ trang tính đã đồng bộ hiện tại, không phải ngày gốc trong lịch sử nhập.";
        }
        if (input.startsWith("MISSING_SOURCE_DATE:") || input.startsWith("MISSING_PUBLISH_TIME")
                || input.startsWith("EMPTY_CONTENT") || input.startsWith("CONTENT_TOO_LONG")
                || input.startsWith("NOT_APPROVED") || input.startsWith("INVALID_POST_STATUS:")
                || input.startsWith("INCOMPATIBLE_MEDIA:")) {
            List<String> reasons = new ArrayList<>();
            for (String item : input.split("; (?=(?:MISSING_SOURCE_DATE|MISSING_PUBLISH_TIME|EMPTY_CONTENT|CONTENT_TOO_LONG|NOT_APPROVED|INVALID_POST_STATUS|INCOMPATIBLE_MEDIA))")) {
                String reason = switch (item.split(":", 2)[0]) {
                    case "MISSING_SOURCE_DATE" -> "Thiếu ngày đăng hợp lệ và khớp duy nhất từ trang tính đã liên kết.";
                    case "MISSING_PUBLISH_TIME" -> "Thiếu giờ đăng.";
                    case "EMPTY_CONTENT" -> "Bài viết chưa có nội dung.";
                    case "CONTENT_TOO_LONG" -> "Nội dung Threads vượt quá 500 ký tự.";
                    case "NOT_APPROVED" -> "Bài viết chưa được duyệt.";
                    case "INVALID_POST_STATUS" -> "Trạng thái bài viết chưa đủ điều kiện đăng.";
                    case "INCOMPATIBLE_MEDIA" -> "Cần ảnh Cloudinary hợp lệ hoặc một video/Reel có đường dẫn video; chưa hỗ trợ kết hợp ảnh và video.";
                    default -> null;
                };
                if (reason == null) return null;
                reasons.add(reason);
            }
            return String.join(" ", reasons);
        }
        String exact = CATALOG.exact().get(input);
        if (exact != null) return exact;
        var row = ROW.matcher(input);
        if (row.matches()) {
            String inner = CATALOG.exact().get(row.group(2).strip());
            if (inner == null) inner = matchPrefix(row.group(2).strip(), CATALOG.prefix());
            if (inner != null) return "Dòng " + row.group(1) + ": " + inner;
        }
        String translated = matchPrefix(input, CATALOG.prefix());
        if (translated == null && errorContext) translated = matchPrefix(input, CATALOG.errorPrefix());
        return translated;
    }

    private static String matchPrefix(String source, List<Rule> rules) {
        for (Rule rule : rules) {
            if (!source.startsWith(rule.source())) continue;
            // Preserve safe HTTP/error codes when present, never arbitrary credentials or raw exception text.
            var http = HTTP.matcher(source);
            var code = META_CODE.matcher(source);
            String out = rule.translation();
            if (http.find()) out += " (HTTP " + http.group(1) + ")";
            if (code.find()) out += " (mã lỗi " + code.group(1) + ")";
            String tail = source.substring(rule.source().length()).strip();
            if (rule.source().endsWith("row ") && tail.matches("[0-9]{1,8}")) out += " (Dòng " + tail + ")";
            if (rule.source().startsWith("To avoid overlapping") && tail.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) out += " Ngày sớm nhất: " + tail + ".";
            return out;
        }
        return null;
    }

    public static String validation(String field, String constraint, String original) {
        String exact = original == null ? null : CATALOG.exact().get(original.strip());
        if (exact != null) return exact;
        String label = fieldLabel(field);
        String type = constraint == null ? "" : constraint;
        return switch (type) {
            case "NotNull", "NotBlank", "NotEmpty" -> "Vui lòng cung cấp " + label + ".";
            case "Email" -> "Địa chỉ email không hợp lệ.";
            case "Size" -> sizeMessage(label, original);
            case "Min", "DecimalMin", "Max", "DecimalMax" -> rangeMessage(label, type, original);
            case "Positive" -> label + " phải lớn hơn 0.";
            case "PositiveOrZero" -> label + " không được là số âm.";
            case "Negative" -> label + " phải nhỏ hơn 0.";
            case "Past", "PastOrPresent" -> label + " phải ở quá khứ hoặc thời điểm được phép.";
            case "Future", "FutureOrPresent" -> label + " phải ở thời điểm được phép trong tương lai.";
            case "Pattern" -> label + " không đúng định dạng.";
            case "AssertTrue" -> "Vui lòng xác nhận " + label + ".";
            default -> original != null && VI.matcher(original).find() ? original : label + " không hợp lệ. Vui lòng kiểm tra lại.";
        };
    }

    private static String sizeMessage(String label, String original) {
        var matcher = Pattern.compile("(?i)size must be between (\\d+) and (\\d+)").matcher(original == null ? "" : original);
        if (matcher.find()) return label + " phải có độ dài hoặc số lượng từ " + matcher.group(1) + " đến " + matcher.group(2) + ".";
        return label + " có độ dài hoặc số lượng không hợp lệ.";
    }

    private static String rangeMessage(String label, String type, String original) {
        var matcher = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(original == null ? "" : original);
        if (matcher.find()) return label + (type.endsWith("Min") ? " phải đạt giới hạn tối thiểu " : " không được vượt quá giới hạn ") + matcher.group() + ".";
        return label + " nằm ngoài phạm vi cho phép.";
    }

    private static String fieldLabel(String field) {
        String key = field == null ? "" : field.replaceAll("\\[\\d+\\]", "");
        return switch (key) {
            case "email" -> "địa chỉ email";
            case "password" -> "mật khẩu";
            case "confirmPassword" -> "mật khẩu xác nhận";
            case "displayName", "name" -> "tên hiển thị";
            case "username" -> "tên đăng nhập";
            case "workspaceId" -> "không gian làm việc";
            case "postId", "postIds" -> "bài viết";
            case "jobId", "jobIds", "publishingJobId" -> "lịch đăng";
            case "socialAccountId", "socialAccountIds", "accountId", "accountIds", "threadsAccountId" -> "tài khoản mạng xã hội";
            case "facebookGroupId", "groupId" -> "nhóm Facebook";
            case "platform" -> "nền tảng";
            case "role" -> "vai trò";
            case "status" -> "trạng thái";
            case "content", "text" -> "nội dung bài viết";
            case "title" -> "tiêu đề";
            case "description" -> "mô tả";
            case "scheduledAt", "publishAt", "publishTime" -> "thời gian đăng";
            case "startAt", "startDate", "startTime" -> "thời gian bắt đầu";
            case "spreadsheetUrl", "sheetUrl" -> "đường dẫn Google Sheets";
            case "groupUrl" -> "đường dẫn nhóm Facebook";
            case "externalPostId" -> "mã bài đã đăng";
            case "publishedUrl", "permalink" -> "đường dẫn bài đã đăng";
            case "externalAccountId" -> "mã tài khoản mạng xã hội";
            case "connectionId" -> "kết nối";
            case "importId", "importIds" -> "lần nhập dữ liệu";
            case "sourceRowNumber", "fromRow", "toRow" -> "dòng dữ liệu nguồn";
            case "intervalMinutes" -> "khoảng cách giữa các bài";
            case "maxPostsPerDay", "maxPer24Hours", "maxTasksPerDay" -> "số bài tối đa trong ngày";
            case "limit", "size", "maxRows", "maxPosts", "maxJobs" -> "số lượng";
            case "page" -> "số trang";
            case "reason" -> "lý do";
            case "note" -> "ghi chú";
            case "evidence" -> "bằng chứng đối soát";
            case "mediaType" -> "loại ảnh hoặc video";
            case "url", "sourceUrl" -> "đường dẫn";
            case "previewToken" -> "kết quả xem trước";
            default -> key.isBlank() ? "thông tin yêu cầu" : "thông tin nhập (" + key + ")";
        };
    }
}
