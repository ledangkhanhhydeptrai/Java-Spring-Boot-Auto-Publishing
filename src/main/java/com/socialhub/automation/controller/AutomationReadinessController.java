package com.socialhub.automation.controller;

import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only operational baseline for a workspace before any bulk automation rollout.
 * Does not return OAuth tokens, enqueue work or change publishing state.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/automation")
@RequiredArgsConstructor
public class AutomationReadinessController {

    private final AccessService access;
    private final JdbcTemplate db;

    public record AccountHealthSummary(
            String platform,
            String status,
            long accounts,
            long missingToken,
            long expiredToken
    ) { }

    public record JobStatusSummary(String platform, String status, long jobs) { }

    public record ReadinessResult(
            UUID workspaceId,
            long totalAccounts,
            long connectedAccounts,
            long publishableDueJobs,
            long needsReviewJobs,
            List<AccountHealthSummary> accountsByPlatform,
            List<JobStatusSummary> jobsByPlatform
    ) { }

    @GetMapping("/readiness")
    public ReadinessResult readiness(@PathVariable UUID workspaceId) {
        access.admin(workspaceId);

        List<AccountHealthSummary> accounts = db.query("""
                SELECT a.platform, a.status,
                       COUNT(*) AS accounts,
                       COUNT(*) FILTER (
                           WHERE a.access_token_encrypted IS NULL
                              OR BTRIM(a.access_token_encrypted) = ''
                       ) AS missing_token,
                       COUNT(*) FILTER (
                           WHERE a.token_expires_at IS NOT NULL
                             AND a.token_expires_at <= NOW()
                       ) AS expired_token
                FROM social_accounts a
                WHERE a.workspace_id = ?
                GROUP BY a.platform, a.status
                ORDER BY a.platform, a.status
                """, (rs, i) -> new AccountHealthSummary(
                rs.getString("platform"), rs.getString("status"),
                rs.getLong("accounts"), rs.getLong("missing_token"),
                rs.getLong("expired_token")), workspaceId);

        List<JobStatusSummary> jobs = db.query("""
                SELECT a.platform, j.status, COUNT(*) AS jobs
                FROM publishing_jobs j
                JOIN social_accounts a ON a.id = j.social_account_id
                JOIN posts p ON p.id = j.post_id
                WHERE p.workspace_id = ? AND a.workspace_id = p.workspace_id
                GROUP BY a.platform, j.status
                ORDER BY a.platform, j.status
                """, (rs, i) -> new JobStatusSummary(
                rs.getString("platform"), rs.getString("status"), rs.getLong("jobs")), workspaceId);

        Long due = db.queryForObject("""
                SELECT COUNT(*)
                FROM publishing_jobs j
                JOIN social_accounts a ON a.id = j.social_account_id
                JOIN posts p ON p.id = j.post_id
                WHERE p.workspace_id = ?
                  AND a.workspace_id = p.workspace_id
                  AND a.platform IN ('FACEBOOK_PAGE', 'THREADS')
                  AND a.status = 'CONNECTED'
                  AND a.access_token_encrypted IS NOT NULL
                  AND BTRIM(a.access_token_encrypted) <> ''
                  AND (a.token_expires_at IS NULL OR a.token_expires_at > NOW())
                  AND j.status = 'PENDING' AND j.attempt_count = 0
                  AND j.external_post_id IS NULL
                  AND j.published_at IS NULL AND j.published_url IS NULL
                  AND p.status IN ('READY','SCHEDULED')
                  AND p.approval_status = 'APPROVED'
                  AND COALESCE(j.scheduled_at, j.created_at) <= NOW()
                  AND NOT EXISTS (
                      SELECT 1 FROM post_media m WHERE m.post_id = p.id
                        AND (m.public_url IS NULL OR BTRIM(m.public_url) = ''
                             OR m.media_type <> 'IMAGE')
                  )
                  AND (NOT EXISTS (
                      SELECT 1 FROM campaign_jobs cj WHERE cj.publishing_job_id = j.id
                  ) OR EXISTS (
                      SELECT 1 FROM campaign_jobs cj
                      JOIN campaigns c ON c.id = cj.campaign_id
                      WHERE cj.publishing_job_id = j.id AND c.status = 'RUNNING'
                  ))
                """, Long.class, workspaceId);

        long totalAccounts = accounts.stream().mapToLong(AccountHealthSummary::accounts).sum();
        long connected = accounts.stream()
                .filter(a -> "CONNECTED".equals(a.status()))
                .mapToLong(AccountHealthSummary::accounts).sum();
        long reviews = jobs.stream().filter(j -> "NEEDS_REVIEW".equals(j.status()))
                .mapToLong(JobStatusSummary::jobs).sum();

        return new ReadinessResult(workspaceId, totalAccounts, connected,
                due == null ? 0 : due, reviews, accounts, jobs);
    }
}
