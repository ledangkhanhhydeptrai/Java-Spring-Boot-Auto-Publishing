package com.socialhub.encodingrepair;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.common.ApiException;
import com.socialhub.post.entity.Post;
import com.socialhub.security.AccessService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time, exact-source Unicode restoration tool. NOT a general post editing endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EncodingRepairService {
    private static final Pattern CODE = Pattern.compile("SH-T10-A(?:0[1-9]|10)-P(?:0[0-9]{2}|100)");
    private static final Charset CP1252 = Charset.forName("windows-1252");
    private static final Set<String> NEVER_MODIFY = Set.of("PUBLISHED", "PROCESSING", "RETRY", "FAILED", "UNKNOWN", "NEEDS_REVIEW");
    private final AccessService access;
    private final EntityManager entityManager;
    private final ObjectMapper json;
    @Value("${app.encoding-repair.backup-dir:encoding-repair-backups}")
    private String backupDir;

    @Value("${app.publisher-enabled:false}")
    private boolean publisherEnabled;
    @Value("${app.threads.publisher-enabled:false}")
    private boolean threadsPublisherEnabled;
    @Value("${app.automation.live.enabled:false}")
    private boolean automationLiveEnabled;

    public record Sample(String code, String action, String currentTopic, String expectedTopic) {
    }

    public record Preview(String token, int sourceRows, int matched, int alreadyCorrect,
                          int fixable, int protectedJobs, int unsafeMismatch, int notFound,
                          List<Sample> samples, boolean executionAllowed) {
    }

    public record ApplyResult(int repaired, int unchanged, int protectedJobs,
                              @JsonSerialize(using = ViDisplayTextSerializer.class) String message) {
    }

    private record TextSource(String topic, String content) {
    }

    private record Change(UUID id, String code, String oldTopic, String oldContent,
                          String newTopic, String newContent, String decision) {
    }

    private record Snapshot(Preview preview, List<Change> changes) {
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID workspaceId, MultipartFile file) {
        access.admin(workspaceId);
        return snapshot(workspaceId, file).preview();
    }

    @Transactional
    public ApplyResult apply(UUID workspaceId, MultipartFile file, String expectedToken, String confirmation) {
        access.admin(workspaceId);
        if (!"RESTORE EXACT EXCEL UTF8".equals(confirmation)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Exact confirmation phrase is required");
        }
        if (publisherEnabled || threadsPublisherEnabled || automationLiveEnabled) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Stop all LIVE publishers and automation workers before restoring scheduled content");
        }
        // Lock every matched Post before verifying the hash and applying a batch repair.
        // This also serializes with normal Post updates. Stop external workers separately.
        Snapshot initial = snapshot(workspaceId, file);
        List<UUID> ids = initial.changes().stream().map(Change::id).filter(Objects::nonNull)
                .distinct().sorted().toList();
        for (UUID id : ids) entityManager.find(Post.class, id, LockModeType.PESSIMISTIC_WRITE);
        Snapshot verified = snapshot(workspaceId, file);
        Preview preview = verified.preview();
        if (expectedToken == null || !MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                preview.token().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.CONFLICT, "Preview changed; reload and review before applying");
        }
        if (preview.sourceRows() != 1000 || preview.matched() != 1000
                || preview.unsafeMismatch() != 0 || preview.notFound() != 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Repair blocked: 1000 unique and safe matching source rows are required");
        }
        // Durable, append-only local backup of originals before any mutation.
        // If storage is not writable, fail closed: do not change the database.
        String backupPath;
        try {
            Path dir = Path.of(backupDir);
            Files.createDirectories(dir);
            Path saved = dir.resolve("SH-T10-" + workspaceId + "-" +
                    Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".json");
            List<Change> changedRows = verified.changes().stream()
                    .filter(change -> "FIXABLE".equals(change.decision())).toList();
            Files.writeString(saved, json.writerWithDefaultPrettyPrinter().writeValueAsString(changedRows),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            backupPath = saved.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Cannot save original content backup on server; no database changes made");
        }
        int repaired = 0;
        for (Change change : verified.changes()) {
            if (!"FIXABLE".equals(change.decision())) continue;
            Post post = entityManager.find(Post.class, change.id());
            if (!Objects.equals(post.getTopic(), change.oldTopic())
                    || !Objects.equals(post.getContent(), change.oldContent())) {
                throw new ApiException(HttpStatus.CONFLICT, "Post changed during recovery; retry preview");
            }
            // Strict byte-derived repair only; never edit authorial content, approvals or schedules.
            post.setTopic(change.newTopic());
            post.setContent(change.newContent());
            repaired++;
            log.warn("[ENCODING RESTORE] workspace={} post={} code={} originalTopic={} restoredTopic={}",
                    workspaceId, change.id(), change.code(), change.oldTopic(), change.newTopic());
        }
        entityManager.flush();
        return new ApplyResult(repaired, preview.alreadyCorrect(), preview.protectedJobs(),
                "Backup: " + backupPath + ". Restored exact source bytes only. Published/processing jobs were not changed. Keep workers paused until re-review.");
    }

    private Snapshot snapshot(UUID workspaceId, MultipartFile file) {
        Map<String, TextSource> excel = parse(file);
        List<Post> existing = entityManager.createQuery(
                        "SELECT p FROM Post p WHERE p.workspace.id = :workspaceId", Post.class)
                .setParameter("workspaceId", workspaceId).getResultList();
        Map<String, Post> byCode = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Post post : existing) {
            String code = extract(post.getTopic());
            if (code == null || !excel.containsKey(code)) continue;
            if (byCode.putIfAbsent(code, post) != null) ambiguous.add(code);
        }
        // Fetch all workspace Job states at once instead of 1000 separate SQL queries.
        Map<UUID, List<String>> statusesByPost = new HashMap<>();
        List<Object[]> workspaceStatuses = entityManager.createQuery(
                        "SELECT j.post.id, j.status FROM PublishingJob j WHERE j.post.workspace.id = :workspaceId", Object[].class)
                .setParameter("workspaceId", workspaceId).getResultList();
        for (Object[] state : workspaceStatuses) {
            statusesByPost.computeIfAbsent((UUID) state[0], key -> new ArrayList<>())
                    .add((String) state[1]);
        }
        int matched = 0, correct = 0, fixable = 0, protectedCount = 0, unsafe = 0, missing = 0;
        List<Sample> samples = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        StringBuilder signature = new StringBuilder(workspaceId.toString());
        for (var item : new TreeMap<>(excel).entrySet()) {
            String code = item.getKey();
            TextSource wanted = item.getValue();
            Post post = byCode.get(code);
            String action;
            if (post == null) {
                action = "NOT_FOUND";
                missing++;
            } else if (ambiguous.contains(code)) {
                action = "AMBIGUOUS";
                unsafe++;
            } else {
                matched++;
                List<String> jobStatuses = statusesByPost.getOrDefault(post.getId(), List.of());
                boolean protectedJob = "PUBLISHED".equals(post.getStatus())
                        || jobStatuses.stream().anyMatch(NEVER_MODIFY::contains);
                if (Objects.equals(post.getTopic(), wanted.topic()) && Objects.equals(post.getContent(), wanted.content())) {
                    action = "ALREADY_CORRECT";
                    correct++;
                } else if (protectedJob) {
                    action = "PROTECTED_JOB";
                    protectedCount++;
                } else if (isExactMojibake(post.getTopic(), wanted.topic())
                        && isExactMojibake(post.getContent(), wanted.content())) {
                    action = "FIXABLE";
                    fixable++;
                } else {
                    action = "UNSAFE_MISMATCH";
                    unsafe++;
                }
                changes.add(new Change(post.getId(), code, post.getTopic(), post.getContent(),
                        wanted.topic(), wanted.content(), action));
                signature.append('|').append(post.getId()).append('|').append(post.getStatus())
                        .append('|').append(post.getApprovalStatus()).append('|').append(jobStatuses)
                        .append('|').append(post.getTopic()).append('|').append(post.getContent());
            }
            signature.append('|').append(code).append('|').append(wanted.topic()).append('|')
                    .append(wanted.content()).append('|').append(action);
            if (samples.size() < 16 && !"ALREADY_CORRECT".equals(action)) {
                samples.add(new Sample(code, action, post == null ? null : post.getTopic(), wanted.topic()));
            }
        }
        boolean allowed = excel.size() == 1000 && matched == 1000 && unsafe == 0 && missing == 0;
        Preview p = new Preview(sha256(signature.toString()), excel.size(), matched, correct,
                fixable, protectedCount, unsafe, missing, List.copyOf(samples), allowed);
        return new Snapshot(p, changes);
    }

    private static boolean isExactMojibake(String actual, String expected) {
        if (actual == null || expected == null) return false;
        if (actual.equals(expected)) return true;
        String windows1252 = new String(expected.getBytes(StandardCharsets.UTF_8), CP1252);
        String latin1 = new String(expected.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        return actual.equals(windows1252) || actual.equals(latin1);
    }

    private static String extract(String text) {
        if (text == null) return null;
        Matcher match = CODE.matcher(text);
        return match.find() ? match.group() : null;
    }

    private Map<String, TextSource> parse(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 10L * 1024 * 1024
                || file.getOriginalFilename() == null || !file.getOriginalFilename().endsWith(".xlsx")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Upload the original .xlsx (up to 10 MB)");
        }
        Map<String, TextSource> source = new LinkedHashMap<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.forLanguageTag("vi-VN"));
            Row header = sheet.getRow(0);
            if (header == null || !"Topic".equals(fmt.formatCellValue(header.getCell(0)).trim())
                    || !"Nội dung".equals(fmt.formatCellValue(header.getCell(1)).trim())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Excel headers Topic / Nội dung do not match");
            }
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                String topic = fmt.formatCellValue(row.getCell(0));
                String content = fmt.formatCellValue(row.getCell(1));
                if (topic.isBlank() && content.isBlank()) continue;
                String code = extract(topic);
                if (code == null || !content.contains(code) || topic.contains("Ã") || topic.contains("á»")
                        || content.contains("Ã") || content.contains("á»")) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid or corrupted Excel source row " + (rowIndex + 1));
                }
                if (source.putIfAbsent(code, new TextSource(topic, content)) != null) {
                    throw new ApiException(HttpStatus.CONFLICT, "Duplicate code " + code);
                }
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot parse the original Excel file");
        }
        if (source.size() != 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Expected 1000 unique SH-T10 rows; got " + source.size());
        }
        return source;
    }

    private static String sha256(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
