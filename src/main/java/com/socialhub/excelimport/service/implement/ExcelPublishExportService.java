package com.socialhub.excelimport.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExcelPublishExportService {
    private final JdbcTemplate jdbc;
    private final AccessService access;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private record PublishedRow(String topic, String content, String publishedUrl,
                                OffsetDateTime publishedAt) {
    }

    private static String normalizeText(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
                .replaceAll("\\s+", " ");
    }

    public Map<String, Integer> progress(UUID workspaceId, UUID importId) {
        access.workspace(workspaceId);
        Integer count = jdbc.query("""
                SELECT 1 FROM excel_imports WHERE id = ? AND workspace_id = ?
                """, rs -> rs.next() ? 1 : null, importId, workspaceId);
        if (count == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Excel import not found");
        }
        return jdbc.query("""
                SELECT
                    COUNT(*) FILTER (WHERE processing_status = 'PUBLISHED') published,
                    COUNT(*) FILTER (WHERE processing_status = 'IMPORTED') awaiting,
                    COUNT(*) FILTER (WHERE processing_status = 'AUTO_SCHEDULED') scheduled,
                    COUNT(*) FILTER (WHERE processing_status = 'AUTO_BLOCKED') blocked,
                    COUNT(*) FILTER (WHERE processing_status LIKE 'SKIPPED_%') skipped
                FROM excel_import_rows WHERE import_id = ?
                """, rs -> {
            rs.next();
            return Map.of(
                    "published", rs.getInt("published"),
                    "awaiting", rs.getInt("awaiting"),
                    "scheduled", rs.getInt("scheduled"),
                    "blocked", rs.getInt("blocked"),
                    "skipped", rs.getInt("skipped")
            );
        }, importId);
    }

    /**
     * Export copy of the exact original XLSX, with published status and link
     * overwritten ONLY for rows known to have been published successfully.
     * Original XLSX stays on the user's machine; the server stores only SHA256.
     */
    public byte[] export(UUID workspaceId, UUID importId, MultipartFile original) {
        access.admin(workspaceId);
        String hash = jdbc.query("""
                        SELECT file_hash FROM excel_imports
                        WHERE id = ? AND workspace_id = ?
                        """, rs -> rs.next() ? rs.getString(1) : null,
                importId, workspaceId);
        if (hash == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Excel import not found");
        }
        if (original == null || original.isEmpty()
                || original.getSize() > 10 * 1024 * 1024
                || original.getOriginalFilename() == null
                || !original.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Original .xlsx file required (max 10 MB)");
        }
        try {
            byte[] bytes = original.getBytes();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equals(hash)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "File does not match original import SHA-256. Select the original Excel file.");
            }

            // This report uses committed publish state, not merely a due schedule.
            Map<Integer, PublishedRow> publishedRows = new HashMap<>();
            jdbc.query("""
                    SELECT e.source_row_number, p.topic, p.content,
                           e.published_url, j.published_at
                    FROM excel_import_rows e
                    JOIN posts p ON p.id = e.post_id
                    LEFT JOIN LATERAL (
                        SELECT pj.published_at
                        FROM publishing_jobs pj
                        WHERE pj.post_id = e.post_id
                          AND pj.status = 'PUBLISHED'
                          AND pj.published_url = e.published_url
                          AND pj.published_at IS NOT NULL
                        ORDER BY pj.published_at DESC, pj.id DESC
                        LIMIT 1
                    ) j ON true
                    WHERE e.import_id = ? AND e.processing_status = 'PUBLISHED'
                    """, rs -> {
                int sourceRow = rs.getInt("source_row_number");
                String publishedUrl = rs.getString("published_url");
                OffsetDateTime publishedAt = rs.getObject("published_at", OffsetDateTime.class);
                if (publishedUrl == null || publishedUrl.isBlank() || publishedAt == null) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "Published row " + sourceRow
                                    + " is missing a matching published Job URL or timestamp.");
                }
                PublishedRow previous = publishedRows.putIfAbsent(sourceRow,
                        new PublishedRow(rs.getString("topic"), rs.getString("content"),
                                publishedUrl, publishedAt));
                if (previous != null) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "Duplicate published source row: " + sourceRow);
                }
            }, importId);

            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Sheet sheet = workbook.getSheetAt(0);
                Row header = sheet.getRow(0);
                if (header == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Missing header");
                DataFormatter fmt = new DataFormatter(Locale.forLanguageTag("vi-VN"));
                int statusCol = -1;
                int linkCol = -1;
                int dateCol = -1;
                int topicCol = -1;
                int contentCol = -1;
                for (Cell cell : header) {
                    String name = Normalizer.normalize(fmt.formatCellValue(cell).trim().toLowerCase(Locale.ROOT),
                            Normalizer.Form.NFC).replaceAll("\\s+", " ");
                    if ("ngày đăng".equals(name)) dateCol = cell.getColumnIndex();
                    if ("trạng thái".equals(name)) statusCol = cell.getColumnIndex();
                    if ("link bài đã đăng".equals(name)) linkCol = cell.getColumnIndex();
                    if ("topic".equals(name)) topicCol = cell.getColumnIndex();
                    if ("nội dung".equals(name)) contentCol = cell.getColumnIndex();
                }
                if (dateCol == -1 || statusCol == -1 || linkCol == -1
                        || topicCol == -1 || contentCol == -1) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "Missing Topic / Nội dung / Ngày đăng / Trạng thái / Link bài đã đăng columns");
                }

                // Validate the exact original row against the imported Post before mutating any cell.
                // Import uses sourceRowNumber = POI row index + 1 (Excel's 1-based row number).
                // Therefore export uses row index = sourceRowNumber - 1; DO NOT subtract 2.
                for (var entry : publishedRows.entrySet()) {
                    int sourceRow = entry.getKey();
                    Row row = sheet.getRow(sourceRow - 1);
                    if (row == null) {
                        throw new ApiException(HttpStatus.CONFLICT, "Original row missing: " + sourceRow);
                    }
                    String excelTopic = normalizeText(fmt.formatCellValue(row.getCell(topicCol)));
                    String excelContent = normalizeText(fmt.formatCellValue(row.getCell(contentCol)));
                    PublishedRow published = entry.getValue();
                    if (!excelTopic.equals(normalizeText(published.topic()))
                            || !excelContent.equals(normalizeText(published.content()))) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "Excel row " + sourceRow + " does not match imported Post. "
                                        + "Export aborted to prevent updating the wrong row.");
                    }
                }

                // Use a real Excel date/time cell, so users can sort and filter by date.
                // One reusable format; preserve each existing cell's other styling.
                short dateFormat = workbook.createDataFormat().getFormat("dd/MM/yyyy HH:mm:ss");
                Map<Short, CellStyle> dateStyles = new HashMap<>();
                for (var entry : publishedRows.entrySet()) {
                    Row row = sheet.getRow(entry.getKey() - 1);
                    PublishedRow published = entry.getValue();

                    Cell date = row.getCell(dateCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    CellStyle originalStyle = date.getCellStyle();
                    short originalStyleIndex = originalStyle.getIndex();
                    CellStyle formatted = dateStyles.get(originalStyleIndex);
                    if (formatted == null) {
                        formatted = workbook.createCellStyle();
                        formatted.cloneStyleFrom(originalStyle);
                        formatted.setDataFormat(dateFormat);
                        dateStyles.put(originalStyleIndex, formatted);
                    }
                    LocalDateTime vietnamTime = LocalDateTime.ofInstant(
                            published.publishedAt().toInstant(), VIETNAM_ZONE);
                    date.setCellValue(vietnamTime);
                    date.setCellStyle(formatted);

                    Cell status = row.getCell(statusCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    status.setCellValue("Đã đăng");
                    Cell link = row.getCell(linkCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    link.setCellValue(published.publishedUrl());
                }
                workbook.write(out);
                return out.toByteArray();
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot export Excel: " + e.getClass().getSimpleName());
        }
    }
}
