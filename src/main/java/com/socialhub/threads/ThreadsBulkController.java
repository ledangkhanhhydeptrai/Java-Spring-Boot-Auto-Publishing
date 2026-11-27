package com.socialhub.threads;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;
import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Explicit opt-in queueing; never silently publishes old imports.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/threads")
@RequiredArgsConstructor
public class ThreadsBulkController {
    private final AccessService access;
    private final JdbcTemplate db;

    public record BulkRequest(List<UUID> jobIds, boolean confirmPublish) {
    }

    public record BulkResult(int queued, List<UUID> jobIds,
                             @JsonSerialize(using = ViDisplayTextSerializer.class) String message) {
    }

    @PostMapping("/bulk-run")
    @Transactional
    public BulkResult queue(@PathVariable UUID workspaceId, @RequestBody BulkRequest request) {
        access.admin(workspaceId);
        if (request == null || !request.confirmPublish())
            throw new ApiException(HttpStatus.BAD_REQUEST, "confirmPublish must be true");
        if (request.jobIds() == null || request.jobIds().isEmpty() || request.jobIds().size() > 100
                || request.jobIds().stream().anyMatch(id -> id == null)
                || request.jobIds().stream().distinct().count() != request.jobIds().size())
            throw new ApiException(HttpStatus.BAD_REQUEST, "Provide 1-100 distinct jobIds");

        // All-or-nothing validation before updating any job.
        for (UUID jobId : request.jobIds()) {
            Integer valid = db.queryForObject("""
                    SELECT COUNT(*) FROM publishing_jobs j
                    JOIN posts p ON p.id=j.post_id
                    JOIN social_accounts a ON a.id=j.social_account_id
                    WHERE j.id=? AND p.workspace_id=? AND a.workspace_id=?
                      AND a.platform='THREADS' AND a.status='CONNECTED'
                      AND p.status IN ('READY','SCHEDULED')
                      AND p.approval_status='APPROVED'
                      AND j.status='PENDING' AND j.attempt_count=0
                      AND NOT EXISTS (SELECT 1 FROM post_media m WHERE m.post_id=p.id
                              AND (m.public_url IS NULL OR BTRIM(m.public_url) = '' OR m.media_type <> 'IMAGE'))
                          AND (SELECT COUNT(*) FROM post_media m WHERE m.post_id=p.id) <= 20
                      AND (NOT EXISTS (SELECT 1 FROM campaign_jobs cj WHERE cj.publishing_job_id=j.id)
                           OR EXISTS (SELECT 1 FROM campaign_jobs cj JOIN campaigns c ON c.id=cj.campaign_id
                                      WHERE cj.publishing_job_id=j.id AND c.status='RUNNING'))
                    """, Integer.class, jobId, workspaceId, workspaceId);
            if (valid == null || valid != 1)
                throw new ApiException(HttpStatus.CONFLICT, "Job not eligible for bulk-run: " + jobId);
        }
        for (UUID jobId : request.jobIds()) {
            int changed = db.update("""
                    UPDATE publishing_jobs SET scheduled_at=NOW()
                    WHERE id=? AND status='PENDING' AND attempt_count=0
                    """, jobId);
            if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "Concurrent job update: " + jobId);
        }
        return new BulkResult(request.jobIds().size(), request.jobIds(),
                "Jobs queued. Publisher must be enabled to execute them.");
    }
}
