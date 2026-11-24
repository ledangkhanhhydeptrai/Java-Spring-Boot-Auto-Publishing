package com.socialhub.excelimport.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.post.entity.Post;
import com.socialhub.postapproval.service.interfaces.PostApprovalService;
import com.socialhub.security.AccessService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit, import-scoped, Threads-only readiness and approval.
 * Preview is read-only. Confirm uses the EXISTING audited PostApprovalService.
 * Never modifies an existing PublishingJob or a post already linked to a job.
 */
@Service
@RequiredArgsConstructor
public class ThreadsBulkApprovalService {
    private final JdbcTemplate db;
    private final AccessService access;
    private final EntityManager entityManager;
    private final PostApprovalService approvals;
    private final TransactionTemplate transactions;

    public record Request(UUID threadsAccountId, Integer maxPosts, Integer fromRow,
                          Integer toRow, Boolean dryRun, Boolean confirmApproval,
                          String previewToken) { }

    public record Row(int sourceRowNumber, UUID postId, String action, String reason,
                      String contentPreview) { }

    public record Result(UUID importId, UUID threadsAccountId, boolean dryRun,
                         String previewToken, int eligible, int alreadyApproved,
                         int blocked, int approved, List<Row> rows, List<String> errors) { }

    private record Source(int row, UUID postId, String content, String topic, String status,
                          String approval, boolean hasJob, boolean mediaReady,
                          boolean hasSourceDate, boolean hasPublishTime) { }

    private record Snapshot(String token, int eligible, int alreadyApproved,
                            int blocked, List<Row> rows) { }

    public Result run(UUID workspaceId, UUID importId, Request request) {
        access.admin(workspaceId);
        validate(request);
        validateSource(workspaceId, importId, request.threadsAccountId());

        boolean preview = !Boolean.FALSE.equals(request.dryRun());
        if (!preview && !Boolean.TRUE.equals(request.confirmApproval())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "confirmApproval=true required for bulk approval");
        }

        Snapshot snapshot = snapshot(importId, request);
        if (preview) {
            return new Result(importId, request.threadsAccountId(), true, snapshot.token(),
                    snapshot.eligible(), snapshot.alreadyApproved(), snapshot.blocked(), 0,
                    snapshot.rows(), List.of());
        }
        if (request.previewToken() == null || !MessageDigest.isEqual(
                request.previewToken().getBytes(StandardCharsets.UTF_8),
                snapshot.token().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Preview expired: data changed. Please preview again before approving.");
        }

        int approved = 0;
        int blocked = snapshot.blocked();
        List<Row> outcomes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Row row : snapshot.rows()) {
            if (!"PREPARE_AND_APPROVE".equals(row.action())) {
                outcomes.add(row);
                continue;
            }
            try {
                transactions.executeWithoutResult(tx -> approveOne(
                        workspaceId, importId, request.threadsAccountId(), row));
                approved++;
                outcomes.add(new Row(row.sourceRowNumber(), row.postId(),
                        "APPROVED", "READY + APPROVED; no Job created", row.contentPreview()));
            } catch (RuntimeException ex) {
                blocked++;
                String reason = ex instanceof ApiException ? ex.getMessage()
                        : "State changed or approval failed; inspect backend logs";
                errors.add("Row " + row.sourceRowNumber() + ": " + reason);
                outcomes.add(new Row(row.sourceRowNumber(), row.postId(),
                        "BLOCKED", reason, row.contentPreview()));
            }
        }
        return new Result(importId, request.threadsAccountId(), false,
                snapshot.token(), snapshot.eligible(), snapshot.alreadyApproved(),
                blocked, approved, List.copyOf(outcomes), List.copyOf(errors));
    }

    private void validate(Request request) {
        if (request == null || request.threadsAccountId() == null || request.maxPosts() == null
                || request.maxPosts() < 1 || request.maxPosts() > 1000
                || (request.fromRow() != null && request.fromRow() < 2)
                || (request.toRow() != null && request.toRow() > 10001)
                || (request.fromRow() != null && request.toRow() != null
                    && request.toRow() < request.fromRow())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "threadsAccountId, maxPosts(1..1000), valid fromRow/toRow required");
        }
    }

    private void validateSource(UUID workspaceId, UUID importId, UUID accountId) {
        Boolean allowed = db.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM excel_imports e JOIN social_accounts a
                      ON a.id=e.social_account_id
                    WHERE e.id=? AND e.workspace_id=? AND a.id=?
                      AND a.workspace_id=e.workspace_id
                      AND a.platform='THREADS' AND a.status='CONNECTED'
                )
                """, Boolean.class, importId, workspaceId, accountId);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Import must belong to the selected CONNECTED Threads account; Facebook imports are not eligible");
        }
    }

    private List<Source> readSource(UUID importId, Request request) {
        return db.query("""
                SELECT r.source_row_number, r.post_id, p.content, p.topic, p.status,
                       p.approval_status,
                       EXISTS(SELECT 1 FROM publishing_jobs j WHERE j.post_id=p.id) AS has_job,
                       NOT EXISTS(SELECT 1 FROM post_media m WHERE m.post_id=p.id
                           AND (m.public_url IS NULL OR btrim(m.public_url)=''
                                OR m.media_type<>'IMAGE'
                                OR m.public_url ILIKE '%/video/upload/%'
                                OR m.source_url ILIKE '%/video/upload/%')) AS media_ready,
                       r.source_publish_date IS NOT NULL AS has_source_date,
                       e.publish_time IS NOT NULL AS has_publish_time
                FROM excel_import_rows r
                JOIN excel_imports e ON e.id=r.import_id
                JOIN posts p ON p.id=r.post_id AND p.workspace_id=e.workspace_id
                WHERE r.import_id=? AND r.post_id IS NOT NULL
                  AND (r.processing_status IS NULL OR r.processing_status NOT LIKE 'SKIPPED_%')
                  AND r.source_row_number BETWEEN ? AND ?
                ORDER BY r.source_row_number
                """, (rs, i) -> new Source(
                        rs.getInt("source_row_number"), rs.getObject("post_id", UUID.class),
                        rs.getString("content"), rs.getString("topic"), rs.getString("status"),
                        rs.getString("approval_status"), rs.getBoolean("has_job"),
                        rs.getBoolean("media_ready"), rs.getBoolean("has_source_date"),
                        rs.getBoolean("has_publish_time")),
                importId, request.fromRow() == null ? 2 : request.fromRow(),
                request.toRow() == null ? 10001 : request.toRow());
    }

    private String reason(Source source) {
        if (source.hasJob()) return "Publishing Job already exists; never modify it";
        if (!Set.of("DRAFT", "READY").contains(source.status()))
            return "Post status is not DRAFT or READY";
        if (!Set.of("NOT_SUBMITTED", "PENDING_APPROVAL", "REJECTED", "APPROVED")
                .contains(source.approval()))
            return "Unsupported approval state";
        if (source.content() == null || source.content().isBlank()) return "Empty content";
        if (source.content().length() > 500) return "Threads exceeds 500 characters";
        if (isVideoTopic(source.topic())) return "VIDEO/REEL needs separate Threads media support";
        if (!source.mediaReady()) return "Media not ready or not IMAGE (VIDEO/REEL requires separate support)";
        if (!source.hasSourceDate() || !source.hasPublishTime())
            return "Missing original Excel date or import publish time";
        return null;
    }

    private Snapshot snapshot(UUID importId, Request request) {
        List<Source> sources = readSource(importId, request);
        List<Row> rows = new ArrayList<>();
        int eligible = 0;
        int already = 0;
        int blocked = 0;
        int blockedSamples = 0;
        StringBuilder fingerprint = new StringBuilder(importId + "|" + request.threadsAccountId()
                + "|" + request.maxPosts() + "|" + request.fromRow() + "|" + request.toRow());
        for (Source source : sources) {
            fingerprint.append("|").append(source); // Include content/state/media flags in snapshot
            String reason = reason(source);
            String excerpt = source.content() == null ? "" : source.content()
                    .substring(0, Math.min(160, source.content().length()));
            if (reason != null) {
                blocked++;
                if (blockedSamples < 30) {
                    rows.add(new Row(source.row(), source.postId(), "BLOCKED", reason, excerpt));
                    blockedSamples++;
                }
                continue;
            }
            if ("APPROVED".equals(source.approval()) && "READY".equals(source.status())) {
                already++;
                continue;
            }
            if (eligible < request.maxPosts()) {
                rows.add(new Row(source.row(), source.postId(), "PREPARE_AND_APPROVE",
                        "Safe to prepare and approve", excerpt));
                eligible++;
            }
        }
        return new Snapshot(sha256(fingerprint.toString()), eligible, already,
                blocked, List.copyOf(rows));
    }

    private void approveOne(UUID workspaceId, UUID importId, UUID accountId, Row chosen) {
        // Recheck import/account ownership and source row inside each transaction.
        validateSource(workspaceId, importId, accountId);
        Boolean linked = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM excel_import_rows r
                   JOIN posts p ON p.id=r.post_id
                   JOIN excel_imports e ON e.id=r.import_id
                   WHERE r.import_id=? AND r.source_row_number=? AND r.post_id=?
                     AND p.workspace_id=? AND e.workspace_id=p.workspace_id
                     AND r.source_publish_date IS NOT NULL AND e.publish_time IS NOT NULL
                     AND (r.processing_status IS NULL OR r.processing_status NOT LIKE 'SKIPPED_%'))
                """, Boolean.class, importId, chosen.sourceRowNumber(), chosen.postId(), workspaceId);
        if (!Boolean.TRUE.equals(linked)) throw new ApiException(HttpStatus.CONFLICT, "Source row changed");

        Post post = entityManager.find(Post.class, chosen.postId(), LockModeType.PESSIMISTIC_WRITE);
        if (post == null || !workspaceId.equals(post.getWorkspace().getId()))
            throw new ApiException(HttpStatus.NOT_FOUND, "Post not found");
        if (!Set.of("DRAFT", "READY").contains(post.getStatus()))
            throw new ApiException(HttpStatus.CONFLICT, "Post is no longer DRAFT/READY");
        if (post.getContent() == null || post.getContent().isBlank()
                || post.getContent().length() > 500 || isVideoTopic(post.getTopic()))
            throw new ApiException(HttpStatus.CONFLICT, "Content changed or is ineligible");
        Boolean safe = db.queryForObject("""
                SELECT NOT EXISTS (SELECT 1 FROM publishing_jobs WHERE post_id=?)
                    AND NOT EXISTS (SELECT 1 FROM post_media WHERE post_id=?
                      AND (public_url IS NULL OR btrim(public_url)='' OR media_type<>'IMAGE'
                           OR public_url ILIKE '%/video/upload/%'
                           OR source_url ILIKE '%/video/upload/%'))
                """, Boolean.class, post.getId(), post.getId());
        if (!Boolean.TRUE.equals(safe))
            throw new ApiException(HttpStatus.CONFLICT, "Job exists or media not ready");
        if (!Set.of("NOT_SUBMITTED", "PENDING_APPROVAL", "REJECTED", "APPROVED")
                .contains(post.getApprovalStatus()))
            throw new ApiException(HttpStatus.CONFLICT, "Approval status changed");

        post.setStatus("READY");
        entityManager.flush();
        if ("NOT_SUBMITTED".equals(post.getApprovalStatus())
                || "REJECTED".equals(post.getApprovalStatus())) {
            approvals.submit(post.getId());
        }
        if (!"APPROVED".equals(post.getApprovalStatus())) {
            approvals.approve(post.getId()); // persists reviewer + approval history
        }
    }

    private static boolean isVideoTopic(String topic) {
        if (topic == null) return false;
        String normalized = topic.toUpperCase(Locale.ROOT);
        return normalized.contains("[VIDEO]") || normalized.contains("[REEL]");
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
