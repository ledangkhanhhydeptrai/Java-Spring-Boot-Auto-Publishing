package com.socialhub.dryrun;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.text.Normalizer;
import java.net.URI;
import java.time.*;
import java.time.format.*;
import java.util.*;

/**
 * In-memory simulation ONLY: no database, no HTTP client, no publishing service.
 */
@Service
@RequiredArgsConstructor
public class DryRunQueueService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final DryRunExcelService excel;

    public record SimulatedJob(int excelRow, String validation, String queueStatus,
                               String mockResult, String scheduledAt, List<String> messages) {
    }

    public record QueueReport(String fileName, int totalRows, int alreadyPublished,
                              int invalid, int scheduledFuture, int pastDueRequiresReschedule, int mediaRequiresReview,
                              int mockPublished,
                              int mockPublishedWithMediaStub, int warnings,
                              boolean externalRequestsSent, boolean databaseWritten,
                              List<SimulatedJob> jobs) {
    }

    public QueueReport simulate(MultipartFile file) {
        // Existing validator owns all file-size, format, header and row-limit checks.
        DryRunExcelService.PreviewResult preview = excel.preview(file);
        List<SimulatedJob> jobs = new ArrayList<>();
        int future = 0, pastDue = 0, mediaReview = 0, mock = 0, mediaMock = 0;
        try (InputStream in = file.getInputStream(); Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("vi-VN"));
            Row header = sheet.getRow(sheet.getFirstRowNum());
            int dateColumn = -1, image1 = -1, image2 = -1;
            for (Cell cell : header) {
                String h = normalize(formatter.formatCellValue(cell));
                if (h.equals("ngay dang")) dateColumn = cell.getColumnIndex();
                if (h.equals("anh 1")) image1 = cell.getColumnIndex();
                if (h.equals("anh 2")) image2 = cell.getColumnIndex();
            }
            Instant now = Instant.now();
            for (DryRunExcelService.RowResult r : preview.rows()) {
                Row row = sheet.getRow(r.excelRow() - 1);
                List<String> messages = new ArrayList<>(r.messages());
                String queueStatus, mockResult = "NOT_EXECUTED", schedule = null;
                if (r.result().equals("ALREADY_PUBLISHED")) {
                    queueStatus = "EXCLUDED_ALREADY_PUBLISHED";
                } else if (r.result().equals("INVALID")) {
                    queueStatus = "REJECTED_INVALID";
                } else {
                    LocalDateTime at = row == null ? null : parseDate(dateColumn < 0 ? null : row.getCell(dateColumn), formatter);
                    if (at == null) {
                        // Excel công ty để trống Ngày đăng để điền sau khi đăng thật.
                        // Mô phỏng không tự đăng: bài chỉ được Import thành DRAFT,
                        // sau đó mới duyệt và lên lịch trên giao diện.
                        queueStatus = "DRAFT_REQUIRES_SCHEDULING";
                        messages.add("Chưa lên lịch: có thể Import thành DRAFT; chưa đưa vào hàng đợi xuất bản");
                        String img1 = row == null ? "" : value(row, image1, formatter);
                        String img2 = row == null ? "" : value(row, image2, formatter);
                        if (!img1.isBlank() || !img2.isBlank()) {
                            mediaReview++;
                            if (!img1.isBlank()) messages.add(mediaCheck("Ảnh 1", img1));
                            if (!img2.isBlank()) messages.add(mediaCheck("Ảnh 2", img2));
                            messages.add("Ảnh cần xử lý sau Import, trước khi duyệt và tạo Publishing Job");
                        }
                    } else {
                        schedule = at.atZone(ZONE).toOffsetDateTime().toString();
                        // Fail closed: historical schedules never auto-publish or "catch up".
                        if (!at.atZone(ZONE).toInstant().isAfter(now)) {
                            queueStatus = "PAST_DUE_REQUIRES_RESCHEDULE";
                            pastDue++;
                            messages.add("Lịch đã quá hạn: phải đặt lại lịch và duyệt thủ công; không đăng bù");
                        } else {
                            String img1 = row == null ? "" : value(row, image1, formatter);
                            String img2 = row == null ? "" : value(row, image2, formatter);
                            if (!img1.isBlank() || !img2.isBlank()) {
                                queueStatus = "MEDIA_REQUIRES_REVIEW";
                                mediaReview++;
                                if (!img1.isBlank()) messages.add(mediaCheck("Ảnh 1", img1));
                                if (!img2.isBlank()) messages.add(mediaCheck("Ảnh 2", img2));
                                messages.add("Chưa tải ảnh, chưa xác nhận MIME/size/accessibility; không đưa vào live queue");
                            } else {
                                queueStatus = "SCHEDULED_FUTURE";
                                future++;
                                messages.add("Lịch tương lai hợp lệ về mặt cú pháp; chưa gửi vào queue thật");
                            }
                        }
                    }
                }
                jobs.add(new SimulatedJob(r.excelRow(), r.result(), queueStatus, mockResult,
                        schedule, List.copyOf(messages)));
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Không thể mô phỏng Excel: " + ex.getClass().getSimpleName());
        }
        return new QueueReport(preview.fileName(), preview.totalRows(), preview.alreadyPublished(),
                preview.invalid(), future, pastDue, mediaReview, mock, mediaMock, preview.warning(), false, false,
                List.copyOf(jobs));
    }

    /**
     * Only syntactic checks. Never downloads a third-party URL.
     */
    private static String mediaCheck(String label, String raw) {
        if (raw.length() > 2048 || raw.chars().anyMatch(ch -> Character.isISOControl(ch)))
            return label + ": giá trị quá dài hoặc có ký tự điều khiển";
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null || uri.getUserInfo() != null)
                return label + ": chưa phải URL HTTP(S) hợp lệ (có thể là đường dẫn file nội bộ)";
            return label + ": URL đúng cú pháp, chưa kiểm tra truy cập và nội dung ảnh";
        } catch (IllegalArgumentException ex) {
            return label + ": URL không hợp lệ hoặc là đường dẫn file nội bộ";
        }
    }

    private static LocalDateTime parseDate(Cell c, DataFormatter fmt) {
        if (c == null || c.getCellType() == CellType.FORMULA || c.getCellType() == CellType.BLANK) return null;
        if (c.getCellType() == CellType.NUMERIC) {
            if (!DateUtil.isValidExcelDate(c.getNumericCellValue())) return null;
            try {
                return DateUtil.getLocalDateTime(c.getNumericCellValue());
            } catch (Exception ex) {
                return null;
            }
        }
        String value = fmt.formatCellValue(c).strip();
        for (String pattern : List.of("yyyy-MM-dd HH:mm", "dd/MM/yyyy HH:mm", "dd/MM/yyyy")) {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern);
            try {
                return LocalDateTime.parse(value, f);
            } catch (DateTimeParseException ignored) {
            }
            try {
                return LocalDate.parse(value, f).atStartOfDay();
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static String value(Row r, int col, DataFormatter fmt) {
        if (col < 0) return "";
        Cell c = r.getCell(col);
        return c == null ? "" : fmt.formatCellValue(c).strip();
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D')
                .strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
