package com.socialhub.dryrun;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DryRunService {

    private final JdbcTemplate db;

    public record DryRunResult(
            UUID jobId,
            String platform,
            String result,
            @JsonSerialize(using = ViDisplayTextSerializer.class) String reason
    ) {
    }

    public List<DryRunResult> preview(UUID workspaceId) {
        return db.query("""
                        SELECT
                            j.id,
                            a.platform,
                            p.content,
                            p.status AS post_status,
                            j.status AS job_status,
                            EXISTS (
                                SELECT 1
                                FROM post_media m
                                WHERE m.post_id = p.id
                            ) AS has_media
                        FROM publishing_jobs j
                        JOIN posts p ON p.id = j.post_id
                        JOIN social_accounts a ON a.id = j.social_account_id
                        WHERE p.workspace_id = ?
                        ORDER BY j.created_at
                        """,
                (rs, rowNum) -> {
                    UUID jobId = rs.getObject("id", UUID.class);
                    String platform = rs.getString("platform");
                    String content = rs.getString("content");
                    String postStatus = rs.getString("post_status");
                    String jobStatus = rs.getString("job_status");
                    boolean hasMedia = rs.getBoolean("has_media");

                    String reason = "Validation passed";

                    if (!"PENDING".equals(jobStatus)) {
                        reason = "Job is not PENDING";
                    } else if (!List.of("READY", "SCHEDULED")
                            .contains(postStatus)) {
                        reason = "Post is not READY/SCHEDULED";
                    } else if (content == null || content.isBlank()) {
                        reason = "Empty content";
                    } else if ("THREADS".equals(platform)
                            && content.codePointCount(0, content.length()) > 500) {
                        reason = "Threads text exceeds 500 characters";
                    } else if ("THREADS".equals(platform) && hasMedia) {
                        reason = "Threads media publishing not supported";
                    }

                    return new DryRunResult(
                            jobId,
                            platform,
                            "Validation passed".equals(reason)
                                    ? "DRY_RUN_SUCCESS"
                                    : "DRY_RUN_SKIPPED",
                            reason
                    );
                },
                workspaceId
        );
    }
}