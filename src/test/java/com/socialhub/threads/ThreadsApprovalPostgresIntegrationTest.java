package com.socialhub.threads;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL-only approval claim test: NO Spring context, scheduler, token, or HTTP client.
 * Uses disposable rows in socialhub_test and always rolls back the transaction.
 * This tests the Threads claim SQL predicate, not the entire publishing service.
 */
class ThreadsApprovalPostgresIntegrationTest {

    @Test
    void threadsClaimRequiresApprovedPost() throws Exception {
        String url = requiredEnv("TEST_DB_URL");
        String user = requiredEnv("TEST_DB_USERNAME");
        String password = requiredEnv("TEST_DB_PASSWORD");

        // Refuse obvious misconfiguration before even opening a connection.
        assertTrue(url.matches("^jdbc:postgresql://[^/?#]+/socialhub_test(?:\\?.*)?$"),
                "TEST_DB_URL must explicitly target /socialhub_test");

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);
            try {
                assertEquals("socialhub_test", scalarString(conn, "SELECT current_database()"),
                        "SAFETY BLOCK: refusing to write outside socialhub_test");

                UUID workspaceId = UUID.randomUUID();
                UUID accountId = UUID.randomUUID();
                execute(conn, "INSERT INTO workspaces (id, name) VALUES (?, ?)",
                        workspaceId, "TEST_THREADS_APPROVAL_" + workspaceId);
                execute(conn, """
                        INSERT INTO social_accounts
                            (id, workspace_id, platform, external_account_id, display_name, status)
                        VALUES (?, ?, 'THREADS', ?, 'TEST - NO REAL ACCOUNT', 'CONNECTED')
                        """, accountId, workspaceId, "test_" + accountId);

                Map<String, Boolean> expected = new LinkedHashMap<>();
                expected.put("NOT_SUBMITTED", false);
                expected.put("PENDING_APPROVAL", false);
                expected.put("REJECTED", false);
                expected.put("APPROVED", true);

                for (var item : expected.entrySet()) {
                    UUID postId = UUID.randomUUID();
                    UUID jobId = UUID.randomUUID();
                    execute(conn, """
                            INSERT INTO posts (id, workspace_id, topic, content, status, approval_status)
                            VALUES (?, ?, ?, 'Integration test only - NEVER publish', 'READY', ?)
                            """, postId, workspaceId, "TEST_" + item.getKey(), item.getKey());
                    execute(conn, """
                            INSERT INTO publishing_jobs
                                (id, post_id, social_account_id, status, attempt_count, scheduled_at)
                            VALUES (?, ?, ?, 'PENDING', 0, NOW() - INTERVAL '1 minute')
                            """, jobId, postId, accountId);

                    // Actual atomic Threads claim UPDATE predicate (no external API calls).
                    // Separate job for each case makes the results independent.
                    boolean claimed = claimWithoutPublishing(conn, jobId);
                    assertEquals(item.getValue(), claimed,
                            "Unexpected Threads claim for approval_status=" + item.getKey());

                    assertEquals(claimed ? "PROCESSING" : "PENDING",
                            jobStatus(conn, jobId), "Unexpected job status: " + item.getKey());
                }
            } finally {
                // Never persist fixtures, even if an assertion or SQL statement fails.
                conn.rollback();
            }
        }
    }

    private static boolean claimWithoutPublishing(Connection conn, UUID jobId) throws SQLException {
        String sql = """
                UPDATE publishing_jobs j
                SET status='PROCESSING', attempt_count=attempt_count+1,
                    processing_started_at=NOW(), locked_until=NOW()+INTERVAL '5 minutes',
                    next_retry_at=NULL
                FROM posts p, social_accounts a
                WHERE j.id=? AND j.post_id=p.id AND j.social_account_id=a.id
                  AND a.platform='THREADS' AND a.status='CONNECTED'
                  AND p.status IN ('READY','SCHEDULED')
                  AND p.approval_status='APPROVED'
                  AND j.status='PENDING' AND j.attempt_count=0
                  AND COALESCE(j.scheduled_at,j.created_at)<=NOW()
                  AND NOT EXISTS (SELECT 1 FROM post_media m WHERE m.post_id=p.id
                      AND (m.public_url IS NULL OR BTRIM(m.public_url) = '' OR m.media_type <> 'IMAGE'))
                  AND (SELECT COUNT(*) FROM post_media m WHERE m.post_id=p.id) <= 20
                  AND (NOT EXISTS (SELECT 1 FROM campaign_jobs cj WHERE cj.publishing_job_id=j.id)
                       OR EXISTS (SELECT 1 FROM campaign_jobs cj JOIN campaigns c ON c.id=cj.campaign_id
                                  WHERE cj.publishing_job_id=j.id AND c.status='RUNNING'))
                RETURNING j.id
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, jobId);
            try (ResultSet rs = stmt.executeQuery()) {
                boolean found = rs.next();
                if (found) assertFalse(rs.next(), "One job was claimed more than once");
                return found;
            }
        }
    }

    private static String jobStatus(Connection conn, UUID jobId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT status FROM publishing_jobs WHERE id = ?")) {
            stmt.setObject(1, jobId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "Test fixture job missing");
                return rs.getString(1);
            }
        }
    }

    private static String scalarString(Connection conn, String sql) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static void execute(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) stmt.setObject(i + 1, args[i]);
            assertEquals(1, stmt.executeUpdate(), "Fixture SQL must change exactly one row");
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "Missing environment variable " + name);
        assertFalse(value.isBlank(), "Environment variable " + name + " is empty");
        return value;
    }
}
