

package com.socialhub.excelimport.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.excelimport.dto.ExcelImportDto;
import com.socialhub.excelimport.entity.ExcelImport;
import com.socialhub.excelimport.entity.ExcelImportRow;
import com.socialhub.excelimport.repository.ExcelImportRepository;
import com.socialhub.excelimport.repository.ExcelImportRowRepository;
import com.socialhub.excelimport.service.interfaces.ExcelImportService;
import com.socialhub.post.entity.Post;
import com.socialhub.post.repository.PostRepository;
import com.socialhub.postmedia.entity.PostMedia;
import com.socialhub.postmedia.repository.PostMediaRepository;
import com.socialhub.security.AccessService;
import com.socialhub.socialaccount.entity.SocialAccount;
import com.socialhub.socialaccount.repository.SocialAccountRepository;
import com.socialhub.workspace.entity.Workspace;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ExcelImportServiceImpl implements ExcelImportService {

    private static final Logger log = LoggerFactory.getLogger(ExcelImportServiceImpl.class);
    private static final int MAX_ROWS = 10_000;
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d/M/uuuu");

    private final PostMediaRepository postMediaRepository;
    private final ExcelImportRowRepository importRowRepository;
    private final ExcelImportRepository imports;
    private final PostRepository posts;
    private final SocialAccountRepository accounts;
    private final AccessService access;
    private final JdbcTemplate jdbc;

    private record ImportRow(
            int rowNumber,
            String topic,
            String content,
            String image1,
            String image2,
            LocalDate publishDate,
            String status,
            String publishedUrl,
            String facebookPublishedUrl,
            String validationError
    ) {
    }

    private record PersistedCounts(long trackingRows, long linkedPostIds, long existingPosts) {
    }

    private record ParsedExcel(
            String sheetName,
            List<ImportRow> rows
    ) {
    }

    private ExcelImportDto.View view(ExcelImport item) {
        return new ExcelImportDto.View(
                item.getId(),
                item.getWorkspace().getId(),
                item.getFileName(),
                item.getFileHash(),
                item.getTotalRows(),
                item.getImportedRows(),
                item.getSkippedRows(),
                item.getCreatedAt()
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExcelImportDto.View importExcel(
            UUID workspaceId,
            MultipartFile file,
            UUID socialAccountId,
            LocalTime publishTime
    ) {
        Workspace workspace = access.workspace(workspaceId);
        access.admin(workspaceId);

        if (file == null || file.isEmpty()) {
            throw badRequest("Excel file is empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw badRequest("Excel file exceeds 10 MB");
        }

        if (socialAccountId == null) {
            throw badRequest("socialAccountId is required");
        }

        if (publishTime == null) {
            throw badRequest("publishTime is required");
        }

        String fileName = file.getOriginalFilename();

        if (fileName == null ||
                !fileName.toLowerCase(Locale.ROOT)
                        .endsWith(".xlsx")) {
            throw badRequest("Only .xlsx files are supported");
        }

        SocialAccount account = accounts.findById(socialAccountId)
                .orElseThrow(() ->
                        badRequest("Social account not found")
                );

        if (!workspaceId.equals(
                account.getWorkspace().getId())) {
            throw badRequest(
                    "Social account belongs to another workspace"
            );
        }

        String platform = account.getPlatform();

        if (!Set.of("FACEBOOK_PAGE", "THREADS")
                .contains(platform)) {
            throw badRequest(
                    "Only FACEBOOK_PAGE and THREADS are supported"
            );
        }

        if (!"CONNECTED".equals(account.getStatus())) {
            throw badRequest(
                    "Social account is not CONNECTED"
            );
        }

        byte[] bytes;

        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw badRequest("Cannot read Excel file");
        }

        String hash = sha256(bytes);

        if (imports.existsByWorkspaceIdAndSocialAccountIdAndFileHash(
                workspaceId,
                socialAccountId,
                hash
        )) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Excel file already imported for this account"
            );
        }

        ParsedExcel parsed = parseExcel(bytes);
        List<ImportRow> rows = parsed.rows();

        if (rows.isEmpty()) {
            throw badRequest("Excel contains no data rows");
        }

        ExcelImport excelImport = new ExcelImport();
        excelImport.setWorkspace(workspace);
        excelImport.setSocialAccount(account);
        excelImport.setPublishTime(publishTime);
        excelImport.setSourceSheetName(parsed.sheetName());
        excelImport.setFileName(fileName);
        excelImport.setFileHash(hash);
        excelImport.setTotalRows(rows.size());
        excelImport.setImportedRows(0);
        excelImport.setSkippedRows(0);

        excelImport = imports.saveAndFlush(excelImport);

        int importedCount = 0;
        int skippedCount = 0;

        for (ImportRow row : rows) {

            // Google Sheets: G is the SHARED status; H is Threads URL and I is
            // Facebook URL. G=Đã đăng alone must NOT imply that both platforms
            // have published the content.
            boolean threadsLinked = isThreadsUrl(row.publishedUrl());
            boolean facebookLinked = isFacebookUrl(row.facebookPublishedUrl())
                    || isFacebookUrl(row.publishedUrl()); // legacy H exports
            boolean sharedPublished = "Đã đăng".equals(row.status());

            // For an ambiguous previously-published row with no platform URL,
            // fail closed instead of risking a duplicate public post.
            boolean ambiguousPublished = sharedPublished
                    && !threadsLinked && !facebookLinked;
            boolean alreadyPublished = "THREADS".equals(platform)
                    ? threadsLinked || ambiguousPublished
                    : facebookLinked || ambiguousPublished;

            // Unknown content in H/I is also a conflict: do not auto-publish.
            boolean unknownLink = (!row.publishedUrl().isBlank()
                    && !threadsLinked && !isFacebookUrl(row.publishedUrl()))
                    || (!row.facebookPublishedUrl().isBlank()
                    && !isFacebookUrl(row.facebookPublishedUrl()));
            if (unknownLink) {
                saveImportRow(excelImport, row, "SKIPPED_INVALID",
                        "Unknown published URL in H/I; manual review required", null);
                skippedCount++;
                continue;
            }

            if (alreadyPublished) {
                String reason = ambiguousPublished
                        ? "G=Đã đăng but H/I have no platform evidence; manual review"
                        : "Target platform already has a published URL";

                saveImportRow(
                        excelImport,
                        row,
                        "SKIPPED_ALREADY_PUBLISHED",
                        reason,
                        null
                );

                skippedCount++;
                continue;
            }

            // 2. Trạng thái không hợp lệ.
            if (!"Chưa đăng".equals(row.status())
                    && !"Đã đăng".equals(row.status())) {
                saveImportRow(
                        excelImport,
                        row,
                        "SKIPPED_INVALID",
                        "Trạng thái không hợp lệ: " + row.status(),
                        null
                );

                skippedCount++;
                continue;
            }

            // Prevent a second Post for the same account + sheet + source row
            // + content, even if a later exported XLSX has a different hash.
            // Existing Jobs must be resumed, not imported again.
            Boolean previousPost = jdbc.queryForObject("""
                            SELECT EXISTS (
                              SELECT 1 FROM excel_import_rows old_row
                              JOIN excel_imports old_import ON old_import.id = old_row.import_id
                              JOIN posts old_post ON old_post.id = old_row.post_id
                              WHERE old_import.workspace_id = ?
                                AND old_import.social_account_id = ?
                                AND old_import.source_sheet_name = ?
                                AND old_row.source_row_number = ?
                                AND old_post.content = ?
                            )
                            """, Boolean.class, workspaceId, socialAccountId,
                    parsed.sheetName(), row.rowNumber(), row.content());
            if (Boolean.TRUE.equals(previousPost)) {
                saveImportRow(excelImport, row, "SKIPPED_ALREADY_IMPORTED",
                        "Existing Post for this account/sheet/row/content; use it instead of creating a duplicate",
                        null);
                skippedCount++;
                continue;
            }

            // 3. Dữ liệu dòng không hợp lệ.
            if (row.content().isBlank()) {
                saveImportRow(
                        excelImport,
                        row,
                        "SKIPPED_INVALID",
                        "Thiếu Nội dung",
                        null
                );

                skippedCount++;
                continue;
            }

            // Nhập nháp không cần ngày đăng trong tương lai và không
            // giới hạn 500 ký tự ở bước import. Validate khi lên lịch Threads.
            // Không đặt lịch đăng cho Post trong bước này.

            // 6. Tạo Post.
            Post post = new Post();

            post.setWorkspace(workspace);
            post.setExcelImport(excelImport);
            post.setSourceRowNumber(row.rowNumber());
            post.setTopic(row.topic());
            post.setContent(row.content());
            post.setStatus("DRAFT");
            post.setScheduledAt(null);
            post.setApprovalStatus("NOT_SUBMITTED");
            post.setApprovedBy(null);
            post.setApprovedAt(null);
            post = posts.save(post);

            // 7. Lưu ảnh nguồn.
            savePostMedia(
                    post,
                    row.image1(),
                    row.image2()
            );

            // 8. Không tạo PublishingJob khi import nháp.
            // Chỉ tạo job sau khi ảnh đã có publicUrl và người dùng đặt lịch.

            // 9. Ghi lịch sử import.
            saveImportRow(
                    excelImport,
                    row,
                    "IMPORTED",
                    null,
                    post
            );

            importedCount++;
        }

        excelImport.setImportedRows(importedCount);
        excelImport.setSkippedRows(skippedCount);

        // Flush all writes before reporting success; count the physical DB rows.
        posts.flush();
        importRowRepository.flush();
        imports.saveAndFlush(excelImport);

        // This verifies the current transaction, not just the in-memory counters.
        PersistedCounts persisted = jdbc.queryForObject("""
                SELECT COUNT(*) AS tracking_rows,
                       COUNT(r.post_id) AS linked_post_ids,
                       COUNT(p.id) AS existing_posts
                FROM excel_import_rows r
                LEFT JOIN posts p ON p.id = r.post_id
                WHERE r.import_id = ?
                """, (rs, rowNum) -> new PersistedCounts(
                rs.getLong("tracking_rows"),
                rs.getLong("linked_post_ids"),
                rs.getLong("existing_posts")
        ), excelImport.getId());

        String databaseName = jdbc.queryForObject("SELECT current_database()", String.class);
        Integer databasePort = jdbc.queryForObject("SELECT inet_server_port()", Integer.class);
        log.info("[EXCEL IMPORT AUDIT] import={} workspace={} db={} dbPort={} " +
                        "expectedRows={} importedPosts={} skipped={} persistedRows={} linkedPostIds={} existingPosts={}",
                excelImport.getId(), workspaceId, databaseName, databasePort,
                rows.size(), importedCount, skippedCount,
                persisted == null ? -1 : persisted.trackingRows(),
                persisted == null ? -1 : persisted.linkedPostIds(),
                persisted == null ? -1 : persisted.existingPosts());

        if (persisted == null
                || persisted.trackingRows() != rows.size()
                || persisted.linkedPostIds() != importedCount
                || persisted.existingPosts() != importedCount) {
            // Roll back the entire import rather than recording 122 imports but zero Posts.
            throw new IllegalStateException(
                    "Excel Import consistency check failed for import=" + excelImport.getId()
                            + ": expectedRows=" + rows.size()
                            + ", expectedPosts=" + importedCount
                            + ", persisted=" + persisted);
        }

        return view(excelImport);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExcelImportDto.View> list(UUID workspaceId) {
        access.workspace(workspaceId);

        return imports
                .findByWorkspaceIdOrderByCreatedAtDesc(workspaceId)
                .stream()
                .map(this::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ExcelImportDto.View detail(
            UUID workspaceId,
            UUID importId
    ) {
        access.workspace(workspaceId);

        ExcelImport item = imports
                .findByIdAndWorkspaceId(importId, workspaceId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Excel import not found"
                ));

        return view(item);
    }

    private ParsedExcel parseExcel(byte[] bytes) {
        List<ImportRow> result = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(bytes))) {

            if (workbook.getNumberOfSheets() == 0) {
                throw badRequest("Excel has no sheets");
            }

            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);

            if (header == null) {
                throw badRequest("Excel header is missing");
            }

            DataFormatter formatter = new DataFormatter(
                    Locale.forLanguageTag("vi-VN")
            );

            Map<String, Integer> columns = new HashMap<>();

            for (Cell cell : header) {
                String name = normalizeHeader(
                        formatter.formatCellValue(cell)
                );

                if (!name.isBlank()) {
                    if (columns.putIfAbsent(
                            name,
                            cell.getColumnIndex()
                    ) != null) {
                        throw badRequest(
                                "Duplicate Excel column: " + name
                        );
                    }
                }
            }

            for (String required : List.of(
                    "nội dung",
                    "topic",
                    "ảnh 1",
                    "ảnh 2",
                    "ngày đăng",
                    "trạng thái",
                    "link bài đã đăng"
            )) {
                if (!columns.containsKey(required)) {
                    throw badRequest(
                            "Missing Excel column: " + required
                    );
                }
            }

            for (int index = 1;
                 index <= sheet.getLastRowNum();
                 index++) {

                Row row = sheet.getRow(index);

                if (row == null ||
                        isEmptyRow(row, columns, formatter)) {
                    continue;
                }

                int rowNumber = index + 1;

                if (result.size() >= MAX_ROWS) {
                    throw badRequest(
                            "Excel exceeds 10000 data rows"
                    );
                }

                String content = cellText(
                        row,
                        columns.get("nội dung"),
                        formatter
                );

                String topic = cellText(
                        row,
                        columns.get("topic"),
                        formatter
                );

                String image1 = cellText(
                        row,
                        columns.get("ảnh 1"),
                        formatter
                );

                String image2 = cellText(
                        row,
                        columns.get("ảnh 2"),
                        formatter
                );

                String status = normalizeStatus(
                        cellText(
                                row,
                                columns.get("trạng thái"),
                                formatter
                        )
                );

                String publishedUrl = cellText(
                        row,
                        columns.get("link bài đã đăng"),
                        formatter
                );

                // I is optional for older Excel templates. New shared-sheet
                // exports keep Threads link in H and Facebook link in I.
                Integer facebookColumn = columns.get("link facebook");
                String facebookPublishedUrl = facebookColumn == null
                        ? ""
                        : cellText(row, facebookColumn, formatter);

                Cell dateCell = row.getCell(
                        columns.get("ngày đăng")
                );

                LocalDate publishDate = null;
                String validationError = null;

                if (dateCell != null &&
                        dateCell.getCellType() != CellType.BLANK) {
                    try {
                        publishDate = parsePublishDate(
                                dateCell,
                                formatter
                        );
                    } catch (IllegalArgumentException e) {
                        validationError =
                                "Row " + rowNumber +
                                        ": invalid Ngày đăng";
                    }
                }

                result.add(new ImportRow(
                        rowNumber,
                        topic,
                        content,
                        image1,
                        image2,
                        publishDate,
                        status,
                        publishedUrl,
                        facebookPublishedUrl,
                        validationError
                ));
            }

            return new ParsedExcel(
                    sheet.getSheetName(),
                    result
            );

        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw badRequest(
                    "Cannot parse Excel: " + e.getMessage()
            );
        }
    }

    private LocalDate parsePublishDate(
            Cell cell,
            DataFormatter formatter
    ) {
        try {
            if (cell.getCellType() == CellType.NUMERIC &&
                    DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue()
                        .toLocalDate();
            }

            if (cell.getCellType() == CellType.FORMULA &&
                    DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue()
                        .toLocalDate();
            }

            String value = formatter
                    .formatCellValue(cell)
                    .trim();

            if (value.isBlank()) {
                return null;
            }

            try {
                return LocalDate.parse(
                        value,
                        DATE_FORMAT
                );
            } catch (DateTimeParseException ignored) {
                return LocalDate.parse(value);
            }

        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid Excel date",
                    e
            );
        }
    }

    private String normalizeHeader(String value) {
        return Normalizer.normalize(
                value.trim().toLowerCase(Locale.ROOT),
                Normalizer.Form.NFC
        ).replaceAll("\\s+", " ");
    }

    private boolean isThreadsUrl(String raw) {
        return matchesHost(raw, "threads.net", "threads.com");
    }

    private boolean isFacebookUrl(String raw) {
        return matchesHost(raw, "facebook.com", "fb.com");
    }

    private boolean matchesHost(String raw, String... domains) {
        if (raw == null || raw.isBlank()) return false;
        try {
            java.net.URI uri = java.net.URI.create(raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            String normalized = host.toLowerCase(Locale.ROOT);
            for (String domain : domains) {
                if (normalized.equals(domain) || normalized.endsWith("." + domain))
                    return true;
            }
            return false;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String normalizeStatus(String value) {
        String normalized = Normalizer.normalize(
                value.trim().toLowerCase(Locale.ROOT),
                Normalizer.Form.NFC
        ).replaceAll("\\s+", " ");

        return switch (normalized) {
            case "đã đăng" -> "Đã đăng";
            case "chưa đăng" -> "Chưa đăng";
            default -> value.trim();
        };
    }

    private boolean isEmptyRow(
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter
    ) {
        for (int index : columns.values()) {
            if (!cellText(
                    row,
                    index,
                    formatter
            ).isBlank()) {
                return false;
            }
        }

        return true;
    }

    private String cellText(
            Row row,
            int column,
            DataFormatter formatter
    ) {
        Cell cell = row.getCell(column);

        if (cell == null) {
            return "";
        }

        return formatter.formatCellValue(cell).trim();
    }

    private void savePostMedia(
            Post post,
            String image1,
            String image2
    ) {
        List<String> images = Arrays.asList(
                image1,
                image2
        );

        for (int index = 0;
             index < images.size();
             index++) {

            String sourceUrl = images.get(index);

            if (sourceUrl == null ||
                    sourceUrl.isBlank()) {
                continue;
            }

            PostMedia media = new PostMedia();
            media.setPost(post);
            media.setSourceUrl(sourceUrl.trim());
            media.setPublicUrl(null);
            media.setMediaType("IMAGE");
            media.setDisplayOrder(index);

            postMediaRepository.save(media);
        }
    }

    private void saveImportRow(
            ExcelImport excelImport,
            ImportRow row,
            String processingStatus,
            String reason,
            Post post
    ) {
        ExcelImportRow item = new ExcelImportRow();

        item.setExcelImport(excelImport);
        item.setSourceRowNumber(row.rowNumber());
        item.setSourceStatus(row.status());
        item.setSourcePublishDate(row.publishDate());
        if (row.validationError() != null && reason == null) {
            reason = row.validationError();
        }
        item.setProcessingStatus(processingStatus);
        item.setReason(reason);
        item.setPost(post);
        // SourcePublishedUrl is a single legacy field. Preserve the target
        // platform permalink when available; never save a Threads URL as
        // evidence of a Facebook publication or vice versa.
        String targetPlatform = excelImport.getSocialAccount().getPlatform();
        String platformUrl = "THREADS".equals(targetPlatform)
                ? (isThreadsUrl(row.publishedUrl()) ? row.publishedUrl() : "")
                : (isFacebookUrl(row.facebookPublishedUrl()) ? row.facebookPublishedUrl()
                : isFacebookUrl(row.publishedUrl()) ? row.publishedUrl() : "");
        item.setSourcePublishedUrl(platformUrl);

        importRowRepository.save(item);
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes);

            return HexFormat.of().formatHex(digest);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    e
            );
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }
}