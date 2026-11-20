package com.socialhub.googlesheets.service;

import com.socialhub.common.ApiException;
import com.socialhub.googlesheets.dto.GoogleSheetsPublishingBridgeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Conservative, read-only reconciliation between a synced Google Sheet and an
 * existing Excel import. Identity requires matching Excel source row AND
 * exact content. Never infer a safe duplicate from row number alone.
 *
 * No writes to Google Sheets, staging, Posts, Jobs, or Bulk Runs.
 */
@Service
@RequiredArgsConstructor
public class GoogleSheetsPublishingBridgeService {
    private final JdbcTemplate db;

    private record Raw(
            int number, String sourceStatus, String publishedUrl, String content,
            String topic, boolean present, boolean eligible, UUID postId,
            String existingContent, int jobCount, int publishedCount,
            boolean otherPostWithSameContent
    ) { }

    @Transactional(readOnly = true)
    public GoogleSheetsPublishingBridgeDto.Preview preview(
            UUID workspaceId, UUID connectionId, UUID importId, int limit, int offset) {
        if (limit < 1 || limit > 200 || offset < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "limit=1..200 and offset>=0 required");
        }
        Boolean connectionExists = db.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM google_sheet_connections
                           WHERE id=? AND workspace_id=?)
            """, Boolean.class, connectionId, workspaceId);
        if (!Boolean.TRUE.equals(connectionExists)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Google Sheets connection not found");
        }
        List<UUID> accounts = db.query("""
            SELECT social_account_id FROM excel_imports
            WHERE id=? AND workspace_id=? AND social_account_id IS NOT NULL
            """, (rs, i) -> rs.getObject(1, UUID.class), importId, workspaceId);
        if (accounts.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Excel import/account not found in workspace");
        }
        UUID accountId = accounts.getFirst();
        // The join is deliberately scoped to this import, this connection, and this workspace.
        // When the Sheet rows have shifted or text differs, the row goes to REVIEW, not LIVE.
        List<Raw> raw = db.query("""
            SELECT s.source_row_number, s.source_status, s.source_published_url,
                   s.content, s.topic, s.present_in_sheet, s.publish_eligible,
                   p.id AS post_id, p.content AS existing_content,
                   COALESCE(jobs.total,0) AS job_count,
                   COALESCE(jobs.published,0) AS published_count,
                   EXISTS (SELECT 1 FROM posts px
                           WHERE px.workspace_id=? AND px.content=s.content
                             AND (p.id IS NULL OR px.id<>p.id)) AS other_post_same_content
            FROM google_sheet_staged_rows s
            LEFT JOIN excel_import_rows r
                ON r.import_id=? AND r.source_row_number=s.source_row_number
            LEFT JOIN posts p ON p.id=r.post_id AND p.workspace_id=?
            LEFT JOIN LATERAL (
                SELECT COUNT(*)::int AS total,
                       COUNT(*) FILTER (WHERE j.status='PUBLISHED')::int AS published
                FROM publishing_jobs j
                WHERE j.post_id=p.id AND j.social_account_id=?
            ) jobs ON true
            WHERE s.connection_id=?
            ORDER BY s.source_row_number
            """, (rs, i) -> new Raw(
                    rs.getInt("source_row_number"),
                    rs.getString("source_status"),
                    rs.getString("source_published_url"),
                    rs.getString("content"),
                    rs.getString("topic"),
                    rs.getBoolean("present_in_sheet"),
                    rs.getBoolean("publish_eligible"),
                    rs.getObject("post_id", UUID.class),
                    rs.getString("existing_content"),
                    rs.getInt("job_count"),
                    rs.getInt("published_count"),
                    rs.getBoolean("other_post_same_content")
            ), workspaceId, importId, workspaceId, accountId, connectionId);

        List<GoogleSheetsPublishingBridgeDto.Row> assessed = new ArrayList<>();
        int sheetPublished=0, sheetUnpublished=0, backendPublished=0;
        int existingJobs=0, matchedWithoutJobs=0, notImported=0, review=0, absent=0;
        for (Raw r : raw) {
            String status = normalize(r.sourceStatus());
            boolean markedPublished = "da dang".equals(status)
                    || (r.publishedUrl()!=null && !r.publishedUrl().isBlank());
            boolean markedUnpublished = "chua dang".equals(status);
            if (markedPublished) sheetPublished++;
            else if (markedUnpublished) sheetUnpublished++;

            String category, reason;
            if (!r.present()) {
                absent++;
                category="MISSING_FROM_SHEET";
                reason="Row no longer present in most recent Google Sheets snapshot; do not publish";
            } else if (markedPublished) {
                category="PUBLISHED_IN_SHEET";
                reason="Source marks this row published or contains a published URL; do not republish";
            } else if (!markedUnpublished || !r.eligible()) {
                review++;
                category="REVIEW_REQUIRED";
                reason="Source status/content not eligible for publishing";
            } else if (r.postId() == null) {
                if (r.otherPostWithSameContent()) {
                    review++;
                    category="POSSIBLE_DUPLICATE";
                    reason="Matching Post content exists elsewhere in this workspace; reconcile manually";
                } else {
                    notImported++;
                    category="NOT_IMPORTED";
                    reason="No matching Excel-import Post at this exact source row; do not auto-create a duplicate";
                }
            } else if (!r.content().equals(r.existingContent())) {
                review++;
                category="CONTENT_MISMATCH";
                reason="Excel Post content differs from Google Sheets row; reconcile manually";
            } else if (r.publishedCount()>0) {
                backendPublished++;
                category="PUBLISHED_IN_BACKEND";
                reason="A Publishing Job for this account is already PUBLISHED; do not republish";
            } else if (r.jobCount()>0) {
                existingJobs++;
                category="EXISTING_JOB";
                reason="Publishing Job exists, including potentially pending/failed jobs; do not create another";
            } else if (r.otherPostWithSameContent()) {
                review++;
                category="POSSIBLE_DUPLICATE";
                reason="Another Post in the workspace has identical content";
            } else {
                matchedWithoutJobs++;
                category="MATCHED_WITHOUT_JOB";
                reason="Matched existing Excel Post, but needs normal approval/media/scheduling checks";
            }
            assessed.add(new GoogleSheetsPublishingBridgeDto.Row(r.number(),category,
                    r.sourceStatus(),r.topic(),r.postId(),r.jobCount(),r.publishedCount(),reason));
        }
        int start = Math.min(offset, assessed.size());
        int end = (int)Math.min((long)assessed.size(), (long)start + limit);
        return new GoogleSheetsPublishingBridgeDto.Preview(
                connectionId,importId,accountId,raw.size(),sheetPublished,sheetUnpublished,
                backendPublished,existingJobs,matchedWithoutJobs,notImported,review,absent,
                offset,limit,List.copyOf(assessed.subList(start,end)),true,
                "Reconciliation preview only. No database writes, new Jobs or Facebook posts. "
                + "Rows require a separate explicitly confirmed, idempotent import/plan step."
        );
    }

    private static String normalize(String value) {
        String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ','d')
                .replaceAll("\\s+", " ");
    }
}
