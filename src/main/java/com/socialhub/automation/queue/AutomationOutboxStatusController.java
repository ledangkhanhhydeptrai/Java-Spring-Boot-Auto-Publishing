package com.socialhub.automation.queue;

import com.socialhub.security.AccessService;
import org.springframework.beans.factory.annotation.Value;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only operational endpoint: never triggers a publish.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/automation/queue")
public class AutomationOutboxStatusController {
    private final JdbcTemplate db;
    private final AccessService access;

    @Value("${app.automation.queue.live-dispatch-enabled:false}")
    private boolean liveDispatchEnabled;

    public record QueueStatus(String state, String platform, long jobs) {
    }

    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable UUID workspaceId) {
        access.admin(workspaceId);
        List<QueueStatus> data = db.query("""
                SELECT o.state,o.platform,COUNT(*) AS jobs
                FROM automation_job_outbox o
                JOIN publishing_jobs j ON j.id=o.publishing_job_id
                JOIN posts p ON p.id=j.post_id
                WHERE p.workspace_id=?
                GROUP BY o.state,o.platform ORDER BY o.platform,o.state
                """, (rs, i) -> new QueueStatus(rs.getString("state"),
                rs.getString("platform"), rs.getLong("jobs")), workspaceId);
        return Map.of("workspaceId", workspaceId, "groups", data,
                "mode", liveDispatchEnabled ? "LIVE_DISPATCH_CONFIGURED" : "OBSERVE_ONLY_NO_META_REQUESTS");
    }
}
