package com.socialhub.googlesheets.service;

import com.socialhub.common.ApiException;
import com.socialhub.excelimport.dto.ExcelImportDto;
import com.socialhub.excelimport.service.interfaces.ExcelImportService;
import com.socialhub.googlesheets.dto.GoogleSheetsDto;
import com.socialhub.googlesheets.dto.Sh26BulkDto;
import com.socialhub.socialaccount.entity.SocialAccount;
import com.socialhub.socialaccount.repository.SocialAccountRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports exactly 100 SH26 posts into EACH of 10 verified Facebook Page accounts.
 * No publishing job / auto-plan / LIVE worker is invoked. No edits to SH-T10.
 * One database transaction: a failure rolls back all 10 account imports.
 */
@Service
@RequiredArgsConstructor
public class Sh26BulkDraftService {
    private static final Pattern CODE = Pattern.compile("SH26-(A\\d{2})-(P\\d{3})");
    private static final Pattern LABEL = Pattern.compile("\\[(IMAGE|VIDEO|REEL)\\]");
    private static final Pattern PAGE_NAME = Pattern.compile("^\\[([^\\]]{2,100})\\]");
    private static final String[] HEADER = {"STT", "Nội dung", "Topic", "Ảnh 1", "Ảnh 2",
            "Ngày đăng", "Trạng thái", "Link bài đã đăng", "Link Facebook"};
    private static final int PAGE_SIZE = 200;
    private final GoogleSheetsPreviewService googleSheets;
    private final SocialAccountRepository accounts;
    private final ExcelImportService excelImport;
    private final JdbcTemplate db;
    private final EntityManager em;

    private record Item(String group, int seq, String code, String pageName, String kind,
                        String content, String topic, String media1, String media2,
                        String date, String status, String publishedUrl, String facebookUrl) {
    }

    private record Snapshot(Map<String, List<Item>> grouped, Map<String, SocialAccount> targets,
                            Sh26BulkDto.Preview preview) {
    }

    public List<Sh26BulkDto.Imported> existingImports(UUID workspaceId) {
        return db.query("""
                SELECT e.source_sheet_name, e.social_account_id, e.id, e.imported_rows, e.skipped_rows
                FROM excel_imports e
                WHERE e.workspace_id=? AND LEFT(e.source_sheet_name, 6)='SH26_A'
                ORDER BY e.source_sheet_name, e.created_at DESC
                """, (rs, idx) -> new Sh26BulkDto.Imported(
                rs.getString(1).replace("SH26_", ""), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getInt(4), rs.getInt(5)), workspaceId);
    }

    /**
     * Read-only diagnostic for an existing SH26 post; does not delete or update data.
     */
    public List<Sh26BulkDto.ExistingPost> existingPosts(UUID workspaceId) {
        List<Sh26BulkDto.ExistingPost> results = new ArrayList<>();
        db.query("""
                SELECT p.id, p.content, p.status, p.import_id, p.topic,
                    EXISTS (SELECT 1 FROM publishing_jobs pj WHERE pj.post_id=p.id) AS has_job
                FROM posts p
                WHERE p.workspace_id=? AND p.content LIKE '%SH26-%'
                ORDER BY p.created_at DESC, p.id DESC
                LIMIT 2000
                """, rs -> {
            Matcher match = CODE.matcher(rs.getString("content"));
            if (match.find()) {
                results.add(new Sh26BulkDto.ExistingPost(
                        match.group(), rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getObject("import_id", UUID.class), rs.getBoolean("has_job"),
                        rs.getString("topic")));
            }
        }, workspaceId);
        return List.copyOf(results);
    }

    /**
     * P001 was previously scheduled independently. It belongs to the 1000 SH26
     * inventory but MUST NOT be imported again or given a second publishing Job.
     * Fail closed unless its content, target Page, media and existing Job match.
     */
    private UUID verifyReusableP001(UUID workspaceId, Item expected, SocialAccount target) {
        List<Map<String, Object>> matches = db.queryForList("""
                SELECT p.id, p.content, p.topic, p.status, p.import_id,
                       e.social_account_id,
                       (SELECT COUNT(*) FROM publishing_jobs j WHERE j.post_id=p.id) AS job_count,
                       (SELECT COUNT(*) FROM publishing_jobs j
                        WHERE j.post_id=p.id AND j.social_account_id=?) AS matching_jobs
                FROM posts p
                LEFT JOIN excel_imports e ON e.id=p.import_id
                WHERE p.workspace_id=? AND p.content LIKE '%SH26-A01-P001%'
                """, target.getId(), workspaceId);
        if (matches.size() != 1) throw new ApiException(HttpStatus.CONFLICT,
                "Expected exactly one existing SH26-A01-P001 Post; do not create duplicates");
        Map<String, Object> row = matches.getFirst();
        String actualContent = (String) row.get("content");
        String actualTopic = (String) row.get("topic");
        Matcher code = CODE.matcher(actualContent == null ? "" : actualContent);
        if (!code.find() || !"SH26-A01-P001".equals(code.group())
                || !Objects.equals(actualContent == null ? "" : actualContent.trim(), expected.content())
                || !Objects.equals(actualTopic == null ? "" : actualTopic.trim(), expected.topic())
                || !target.getId().equals(row.get("social_account_id"))
                || ((Number) row.get("job_count")).intValue() != 1
                || ((Number) row.get("matching_jobs")).intValue() != 1) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Existing P001 content, Page or Publishing Job differs from Google Sheets; manual review required");
        }
        UUID postId = (UUID) row.get("id");
        List<String> actualMedia = db.queryForList(
                "SELECT source_url FROM post_media WHERE post_id=?", String.class, postId);
        List<String> expectedMedia = new ArrayList<>();
        if (!expected.media1().isBlank()) expectedMedia.add(expected.media1());
        if (!expected.media2().isBlank()) expectedMedia.add(expected.media2());
        if (actualMedia.size() != expectedMedia.size()
                || !new HashSet<>(actualMedia).equals(new HashSet<>(expectedMedia))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Existing P001 media differs from Sheet; review without deleting or reimporting");
        }
        return postId;
    }

    /**
     * Read-only gate for the legacy P001 when scheduling 999 NEW SH26 Jobs.
     */
    public boolean hasReusableP001Job(UUID workspaceId, UUID pageAccountId) {
        Integer count = db.queryForObject("""
                SELECT COUNT(*) FROM posts p
                JOIN excel_imports e ON e.id=p.import_id
                WHERE p.workspace_id=? AND p.content LIKE '%SH26-A01-P001%'
                  AND e.social_account_id=?
                  AND (SELECT COUNT(*) FROM publishing_jobs j WHERE j.post_id=p.id)=1
                  AND (SELECT COUNT(*) FROM publishing_jobs j WHERE j.post_id=p.id
                       AND j.social_account_id=?)=1
                """, Integer.class, workspaceId, pageAccountId, pageAccountId);
        return count != null && count == 1;
    }

    public Sh26BulkDto.Preview preview(UUID workspaceId, Sh26BulkDto.Request request) {
        return snapshot(workspaceId, request).preview();
    }

    @Transactional(rollbackFor = Exception.class)
    public Sh26BulkDto.Result confirm(UUID workspaceId, Sh26BulkDto.Confirm request) {
        if (!"IMPORT_SH26_1000_DRAFTS".equals(request.confirmation())) {
            throw bad("Confirmation phrase must be IMPORT_SH26_1000_DRAFTS");
        }
        // PostgreSQL transaction advisory lock: two concurrent SH26 imports cannot both pass duplicate check.
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class,
                "sh26:" + workspaceId);
        Snapshot snapshot = snapshot(workspaceId, request.request());
        Sh26BulkDto.Preview preview = snapshot.preview();
        if (!preview.valid() || !MessageDigest.isEqual(
                preview.previewToken().getBytes(StandardCharsets.UTF_8),
                request.previewToken().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "SH26 data/mapping changed or existing posts detected. Preview again; no drafts were created.");
        }
        // Fresh import: 1000 new; existing verified P001: 999 new + 1 reused.
        final boolean reuseP001 = preview.existingSh26Posts() == 1;
        List<Sh26BulkDto.Imported> imported = new ArrayList<>();
        int sum = 0;
        for (int i = 1; i <= 10; i++) {
            String group = "A" + String.format(Locale.ROOT, "%02d", i);
            UUID accountId = snapshot.targets().get(group).getId();
            int required = reuseP001 && "A01".equals(group) ? 99 : 100;
            List<Item> toImport = snapshot.grouped().get(group).stream()
                    .filter(item -> !(reuseP001 && "SH26-A01-P001".equals(item.code())))
                    .toList();
            byte[] xlsx = createWorkbook(group, toImport);
            ExcelImportDto.View result = excelImport.importExcel(workspaceId,
                    new InMemoryXlsx("SH26_" + group + ".xlsx", xlsx), accountId, LocalTime.of(9, 0));
            if (result.importedRows() != required || result.skippedRows() != 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Expected exactly " + required + " NEW DRAFT posts for " + group + "; transaction rolled back");
            }
            // The legacy Excel importer marks ALL media IMAGE with publicUrl=null.
            // Correct these exact SH26 imports before commit; direct verified Cloudinary URLs
            // do not require download/re-upload. Media class stays IMAGE/VIDEO/REEL.
            em.flush();
            List<Map<String, Object>> media = db.queryForList("""
                    SELECT pm.id,pm.source_url,p.topic FROM post_media pm
                    JOIN posts p ON p.id=pm.post_id WHERE p.import_id=?
                    """, result.id());
            for (Map<String, Object> m : media) {
                String topic = (String) m.get("topic");
                String kind = kind(topic);
                String url = (String) m.get("source_url");
                if (!validMedia(url, kind)) {
                    throw bad("Invalid Cloudinary media on import " + group);
                }
                db.update("UPDATE post_media SET media_type=?,public_url=? WHERE id=?",
                        kind, url, m.get("id"));
            }
            imported.add(new Sh26BulkDto.Imported(group, accountId, result.id(),
                    result.importedRows(), result.skippedRows()));
            sum += result.importedRows();
        }
        if (sum != (reuseP001 ? 999 : 1000))
            throw new IllegalStateException("SH26 import count mismatch; rolled back");
        return new Sh26BulkDto.Result(10, sum, false, List.copyOf(imported));
    }

    private Snapshot snapshot(UUID workspaceId, Sh26BulkDto.Request request) {
        if (request == null || request.tabName() == null || !"Trang tính1".equals(request.tabName())
                || request.spreadsheetUrl() == null || request.targets() == null
                || request.targets().size() != 10) {
            throw bad("Expected Trang tính1 and exactly 10 explicit SH26 target mappings");
        }
        Map<String, SocialAccount> targets = new LinkedHashMap<>();
        Set<UUID> ids = new HashSet<>();
        for (int i = 1; i <= 10; i++) {
            String group = "A" + String.format(Locale.ROOT, "%02d", i);
            UUID id = request.targets().get(group);
            if (id == null || !ids.add(id)) throw bad("Every group must target a different Page: " + group);
            SocialAccount account = accounts.findById(id)
                    .orElseThrow(() -> bad("Social account missing for " + group));
            if (!workspaceId.equals(account.getWorkspace().getId())
                    || !"FACEBOOK_PAGE".equals(account.getPlatform())
                    || !"CONNECTED".equals(account.getStatus())) {
                throw bad("Invalid / disconnected target for " + group);
            }
            targets.put(group, account);
        }
        Map<String, List<Item>> grouped = new LinkedHashMap<>();
        for (String group : targets.keySet()) grouped.put(group, new ArrayList<>());
        Set<String> seen = new HashSet<>();
        List<String> errors = new ArrayList<>();
        StringBuilder hashContent = new StringBuilder(request.spreadsheetUrl()).append('|').append(request.tabName());
        int rows = 0, images = 0, videos = 0, reels = 0;
        for (int first = 1; first <= 1201; first += PAGE_SIZE) {
            int last = first + PAGE_SIZE - 1;
            var response = googleSheets.preview(new GoogleSheetsDto.PreviewRequest(request.spreadsheetUrl(),
                    "'Trang tính1'!A" + first + ":I" + last));
            if (first == 1) requireHeaders(response.rows());
            for (var row : response.rows()) {
                if (row.rowNumber() == 1 || row.cells().stream().allMatch(String::isBlank)) continue;
                rows++;
                String content = cell(row.cells(), 1);
                Matcher match = CODE.matcher(content);
                if (!match.find() || !match.group(1).matches("A(0[1-9]|10)")
                        || !match.group(2).matches("P(0[0-9][1-9]|0[1-9][0-9]|100)")) {
                    errors.add("Invalid SH26 code on row " + row.rowNumber());
                    continue;
                }
                String group = match.group(1), code = match.group();
                int seq = Integer.parseInt(match.group(2).substring(1));
                String topic = cell(row.cells(), 2);
                Matcher page = PAGE_NAME.matcher(topic);
                String name = page.find() ? page.group(1).trim() : "";
                String type = kind(topic);
                if (!seen.add(code)) errors.add("Duplicate " + code);
                SocialAccount account = targets.get(group);
                if (account == null || !norm(name).equals(norm(account.getDisplayName()))) {
                    errors.add("Group " + group + " does not match Page name: " + name);
                }
                if (type.isEmpty()) errors.add("Missing [IMAGE]/[VIDEO]/[REEL] at " + code);
                String m1 = cell(row.cells(), 3), m2 = cell(row.cells(), 4);
                if ("IMAGE".equals(type)) {
                    images++;
                    if (!validMedia(m1, "IMAGE") || (!m2.isBlank() && !validMedia(m2, "IMAGE")))
                        errors.add("Invalid IMAGE URL at " + code);
                } else if ("VIDEO".equals(type) || "REEL".equals(type)) {
                    if ("VIDEO".equals(type)) videos++;
                    else reels++;
                    if (!validMedia(m1, type) || !m2.isBlank()) errors.add("Invalid VIDEO/REEL media at " + code);
                }
                String status = cell(row.cells(), 6);
                String published = cell(row.cells(), 7), fb = cell(row.cells(), 8);
                if (!"chua dang".equals(norm(status)) || !published.isBlank() || !fb.isBlank()) {
                    errors.add("Already published or unknown status at " + code);
                }
                Item item = new Item(group, seq, code, name, type, content, topic, m1, m2,
                        cell(row.cells(), 5), status, published, fb);
                grouped.get(group).add(item);
                hashContent.append('|').append(group).append(':').append(account.getId());
                for (String value : new String[]{content, topic, m1, m2, item.date(), status, published, fb})
                    hashContent.append('\u001F').append(value);
            }
            if (response.returnedRows() < PAGE_SIZE) break;
        }
        if (rows != 1000 || seen.size() != 1000 || images != 340 || videos != 330 || reels != 330)
            errors.add("Expected 1000 unique SH26 posts = 340 IMAGE + 330 VIDEO + 330 REEL");
        List<Sh26BulkDto.Group> groups = new ArrayList<>();
        for (String group : targets.keySet()) {
            List<Item> items = grouped.get(group);
            items.sort(Comparator.comparingInt(Item::seq));
            boolean[] codes = new boolean[101];
            for (Item item : items) codes[item.seq()] = true;
            if (items.size() != 100) errors.add(group + " does not have 100 posts");
            for (int j = 1; j <= 100; j++) if (!codes[j]) errors.add(group + " missing P" + j);
            groups.add(new Sh26BulkDto.Group(group, items.size(),
                    items.isEmpty() ? "" : items.getFirst().pageName(), targets.get(group).getDisplayName(),
                    targets.get(group).getExternalAccountId(), "IMAGE,VIDEO,REEL"));
        }
        int existing = 0;
        String existingCode = "";
        // No destructive action: only one previously scheduled P001 may be reused.
        // Any other existing SH26 Post blocks this import until reconciled.
        for (Map<String, Object> r : db.queryForList(
                "SELECT content FROM posts WHERE workspace_id=? AND content LIKE '%SH26-%'", workspaceId)) {
            Matcher m = CODE.matcher((String) r.get("content"));
            if (m.find() && seen.contains(m.group())) {
                existing++;
                existingCode = m.group();
            }
        }
        if (existing > 1 || (existing == 1 && !"SH26-A01-P001".equals(existingCode))) {
            errors.add("SH26 posts other than the one verified P001 exist; stop and reconcile before importing");
        } else if (existing == 1) {
            Item expected = grouped.get("A01").stream()
                    .filter(item -> "SH26-A01-P001".equals(item.code()))
                    .findFirst().orElse(null);
            if (expected == null) {
                errors.add("Google Sheets is missing P001 for reuse");
            } else {
                try {
                    UUID oldPostId = verifyReusableP001(workspaceId, expected, targets.get("A01"));
                    hashContent.append("|REUSE_P001=").append(oldPostId);
                } catch (ApiException ex) {
                    errors.add("Existing P001 cannot be reused safely: " + ex.getMessage());
                }
            }
        }
        if (!existingImports(workspaceId).isEmpty()) {
            errors.add("SH26 bulk imports already exist; reconcile imports before running again");
        }
        return new Snapshot(grouped, targets, new Sh26BulkDto.Preview(rows, images, videos, reels,
                existing, sha256(hashContent.toString()), errors.isEmpty(), List.copyOf(groups),
                List.copyOf(errors.stream().limit(60).toList()), false));
    }

    private static void requireHeaders(List<GoogleSheetsDto.SheetRow> rows) {
        if (rows.isEmpty()) throw bad("Google Sheet has no header");
        List<String> c = rows.getFirst().cells();
        for (int i = 1; i <= 7; i++) {
            String[] expected = {"noi dung", "topic", "anh 1", "anh 2", "ngay dang", "trang thai", "link bai da dang"};
            if (!norm(cell(c, i)).equals(expected[i - 1]))
                throw bad("Unexpected Google Sheets header column " + (i + 1));
        }
    }

    private static String cell(List<String> data, int i) {
        return i < data.size() ? data.get(i).trim() : "";
    }

    private static String kind(String topic) {
        Matcher m = LABEL.matcher(topic);
        return m.find() ? m.group(1) : "";
    }

    private static boolean validMedia(String raw, String kind) {
        try {
            URI url = URI.create(raw);
            if (!"https".equals(url.getScheme()) || !"res.cloudinary.com".equals(url.getHost())
                    || url.getUserInfo() != null || url.getPort() != -1 || url.getFragment() != null) return false;
            String path = url.getPath();
            String type = "IMAGE".equals(kind) ? "image" : "video";
            return path.matches("^/[A-Za-z0-9_-]+/" + type + "/upload/.+");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String norm(String text) {
        return Normalizer.normalize(text == null ? "" : text.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private static byte[] createWorkbook(String group, List<Item> items) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("SH26_" + group);
            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADER.length; c++) header.createCell(c).setCellValue(HEADER[c]);
            for (Item item : items) {
                Row row = sheet.createRow(item.seq());
                String[] vals = {String.valueOf(item.seq()), item.content(), item.topic(), item.media1(),
                        item.media2(), item.date(), "Chưa đăng", "", ""};
                for (int c = 0; c < vals.length; c++) row.createCell(c).setCellValue(vals[c]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot prepare SH26 drafts", ex);
        }
    }

    private record InMemoryXlsx(String filename, byte[] content) implements MultipartFile {
        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            try (OutputStream out = new FileOutputStream(dest)) {
                out.write(content);
            }
        }
    }
}
