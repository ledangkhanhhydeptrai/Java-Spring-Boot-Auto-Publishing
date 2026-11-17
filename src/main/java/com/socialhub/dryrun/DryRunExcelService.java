package com.socialhub.dryrun;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.text.Normalizer;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DryRunExcelService {
    private static final int MAX_ROWS = 10_000;
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy")
    };

    public record RowResult(int excelRow, String result, List<String> messages) {
    }

    public record PreviewResult(String fileName, String sheetName, int totalRows,
                                int valid, int warning, int invalid,
                                int alreadyPublished, int duplicateRows,
                                boolean externalRequestsSent, boolean databaseWritten,
                                List<RowResult> rows) {
    }

    public PreviewResult preview(MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("File Excel trống");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw bad("Chỉ hỗ trợ .xlsx");
        if (file.getSize() > MAX_BYTES) throw bad("File vượt quá 10 MB");

        try (InputStream in = file.getInputStream(); Workbook workbook = new XSSFWorkbook(in)) {
            if (workbook.getNumberOfSheets() == 0) throw bad("Không có sheet");
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) throw bad("Thiếu hàng tiêu đề");
            DataFormatter fmt = new DataFormatter(Locale.forLanguageTag("vi-VN"));
            Map<String, Integer> columns = new HashMap<>();
            for (Cell cell : header) {
                String key = normalize(fmt.formatCellValue(cell));
                if (!key.isBlank()) columns.putIfAbsent(key, cell.getColumnIndex());
            }
            int contentCol = required(columns, "noi dung");
            int topicCol = optional(columns, "topic");
            int dateCol = optional(columns, "ngay dang");
            int statusCol = optional(columns, "trang thai");
            int linkCol = optional(columns, "link bai da dang");
            int image1Col = optional(columns, "anh 1");
            int image2Col = optional(columns, "anh 2");

            List<RowResult> results = new ArrayList<>();
            Map<String, Integer> seen = new HashMap<>();
            int valid = 0, warning = 0, invalid = 0, published = 0, duplicates = 0;
            int scanned = 0;
            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlank(row, fmt)) continue;
                if (++scanned > MAX_ROWS) throw bad("File vượt quá " + MAX_ROWS + " dòng dữ liệu");
                List<String> messages = new ArrayList<>();
                String content = value(row, contentCol, fmt);
                String topic = value(row, topicCol, fmt);
                String status = value(row, statusCol, fmt);
                String link = value(row, linkCol, fmt);
                String image1 = value(row, image1Col, fmt);
                String image2 = value(row, image2Col, fmt);
                String result = "VALID";
                boolean hasFormula = formula(row, contentCol) || formula(row, dateCol) || formula(row, statusCol);
                if (hasFormula) {
                    messages.add("Cột quan trọng chứa công thức; không thực thi công thức trong Dry Run");
                    result = "INVALID";
                }
                if (content.isBlank()) {
                    messages.add("Thiếu nội dung");
                    result = "INVALID";
                } else {
                    String key = content.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                    Integer first = seen.putIfAbsent(key, i + 1);
                    if (first != null) {
                        duplicates++;
                        messages.add("Nội dung trùng dòng " + first);
                        if (!"INVALID".equals(result)) result = "WARNING";
                    }
                    if (content.codePointCount(0, content.length()) > 500) {
                        messages.add("Nội dung vượt 500 ký tự: không phù hợp Threads text-only");
                        if (!"INVALID".equals(result)) result = "WARNING";
                    }
                }
                if (topic.isBlank()) {
                    messages.add("Topic trống");
                    if ("VALID".equals(result)) result = "WARNING";
                }
                if (!image1.isBlank() || !image2.isBlank()) {
                    messages.add("Có ảnh/link ảnh: cần pipeline media riêng khi đăng thật");
                    if ("VALID".equals(result)) result = "WARNING";
                }
                // "Ngày đăng" trong mẫu công ty là ngày đã đăng thực tế.
                // Ô trống là hợp lệ: Import chỉ tạo DRAFT, lịch đăng được đặt sau trên UI.
                // Chỉ báo lỗi khi người dùng đã nhập giá trị ngày nhưng sai định dạng.
                if (dateCol >= 0) {
                    Cell dateCell = row.getCell(dateCol);
                    if (!blankDate(dateCell, fmt) && !validDate(dateCell, fmt)) {
                        messages.add("Ngày đăng có giá trị nhưng sai định dạng (Excel date, yyyy-MM-dd HH:mm, dd/MM/yyyy)");
                        result = "INVALID";
                    }
                }
                String normalizedStatus = normalize(status);
                boolean already = normalizedStatus.contains("da dang") || !link.isBlank();
                if (already) {
                    result = "ALREADY_PUBLISHED";
                    messages.add("Đã đăng hoặc có link bài đã đăng: không được đưa vào queue live");
                }
                results.add(new RowResult(i + 1, result, List.copyOf(messages)));
                switch (result) {
                    case "VALID" -> valid++;
                    case "WARNING" -> warning++;
                    case "INVALID" -> invalid++;
                    case "ALREADY_PUBLISHED" -> published++;
                    default -> throw new IllegalStateException("Unexpected result");
                }
            }
            return new PreviewResult(name, sheet.getSheetName(), scanned,
                    valid, warning, invalid, published, duplicates, false, false, List.copyOf(results));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw bad("Không đọc được file Excel: " + e.getClass().getSimpleName());
        }
    }

    private static boolean blankDate(Cell cell, DataFormatter fmt) {
        return cell == null || cell.getCellType() == CellType.BLANK
                || (cell.getCellType() != CellType.FORMULA
                && fmt.formatCellValue(cell).isBlank());
    }

    private static boolean validDate(Cell cell, DataFormatter fmt) {
        if (cell == null || cell.getCellType() == CellType.BLANK || cell.getCellType() == CellType.FORMULA)
            return false;
        if (cell.getCellType() == CellType.NUMERIC) {
            if (!DateUtil.isValidExcelDate(cell.getNumericCellValue())) return false;
            try {
                DateUtil.getLocalDateTime(cell.getNumericCellValue());
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        String raw = fmt.formatCellValue(cell).strip();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                LocalDateTime.parse(raw, f);
                return true;
            } catch (DateTimeParseException ignored) {
            }
            try {
                LocalDate.parse(raw, f);
                return true;
            } catch (DateTimeParseException ignored) {
            }
        }
        return false;
    }

    private static boolean formula(Row row, int col) {
        return col >= 0 && row.getCell(col) != null && row.getCell(col).getCellType() == CellType.FORMULA;
    }

    private static boolean isBlank(Row row, DataFormatter fmt) {
        for (Cell cell : row) if (!fmt.formatCellValue(cell).isBlank()) return false;
        return true;
    }

    private static String value(Row row, int col, DataFormatter fmt) {
        if (col < 0) return "";
        Cell cell = row.getCell(col);
        return cell == null ? "" : fmt.formatCellValue(cell).strip();
    }

    private static int required(Map<String, Integer> cols, String key) {
        Integer value = cols.get(key);
        if (value == null) throw bad("Thiếu cột bắt buộc: " + key);
        return value;
    }

    private static int optional(Map<String, Integer> cols, String key) {
        return cols.getOrDefault(key, -1);
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D')
                .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
