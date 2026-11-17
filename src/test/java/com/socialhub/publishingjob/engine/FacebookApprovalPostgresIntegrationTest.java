package com.socialhub.publishingjob.engine;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL-level integration tests for the Facebook claim predicates.
 * No Spring context, worker execution, OAuth token, scheduler or Meta HTTP calls.
 * All fixtures are inserted into socialhub_test in a transaction that is always rolled back.
 * IMPORTANT: This mirrors the SQL from FanpageQueueProcessor, rather than invoking its private methods.
 */
class FacebookApprovalPostgresIntegrationTest {

    @Test
    void regularFacebookClaimRequiresApprovedPost() throws Exception {
        withTestDatabase(conn -> {
            UUID workspaceId = UUID.randomUUID();
            UUID accountId = createAccount(conn, workspaceId);
            for (var entry : approvalCases().entrySet()) {
                UUID jobId = createJob(conn, workspaceId, accountId, entry.getKey());
                boolean claimed = claimRegularWithoutPublishing(conn, jobId);
                assertEquals(entry.getValue(), claimed, "Regular claim: " + entry.getKey());
                assertEquals(claimed ? "PROCESSING" : "PENDING", jobStatus(conn, jobId));
                assertEquals(claimed ? 1 : 0, attemptCount(conn, jobId));
            }
        });
    }

    @Test
    void facebookBulkClaimRequiresApprovedPost() throws Exception {
        withTestDatabase(conn -> {
            UUID workspaceId = UUID.randomUUID();
            UUID accountId = createAccount(conn, workspaceId);
            for (var entry : approvalCases().entrySet()) {
                UUID jobId = createJob(conn, workspaceId, accountId, entry.getKey());
                UUID bulkRunId = UUID.randomUUID();
                execute(conn, """
                        INSERT INTO facebook_bulk_runs (id, workspace_id, status)
                        VALUES (?, ?, 'RUNNING')
                        """, bulkRunId, workspaceId);
                execute(conn, """
                        INSERT INTO facebook_bulk_run_jobs (bulk_run_id, publishing_job_id)
                        VALUES (?, ?)
                        """, bulkRunId, jobId);
                boolean claimed = claimBulkWithoutPublishing(conn, jobId);
                assertEquals(entry.getValue(), claimed, "Bulk claim: " + entry.getKey());
                assertEquals(claimed ? "PROCESSING" : "PENDING", jobStatus(conn, jobId));
                assertEquals(claimed ? 1 : 0, attemptCount(conn, jobId));
            }
        });
    }

    private static Map<String, Boolean> approvalCases() {
        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("NOT_SUBMITTED", false);
        expected.put("PENDING_APPROVAL", false);
        expected.put("REJECTED", false);
        expected.put("APPROVED", true);
        return expected;
    }

    private static UUID createAccount(Connection conn, UUID workspaceId) throws SQLException {
        UUID accountId = UUID.randomUUID();
        execute(conn, "INSERT INTO workspaces (id, name) VALUES (?, ?)",
                workspaceId, "TEST_FB_APPROVAL_" + workspaceId);
        execute(conn, """
                INSERT INTO social_accounts
                  (id, workspace_id, platform, external_account_id, display_name, status)
                VALUES (?, ?, 'FACEBOOK_PAGE', ?, 'TEST - NO REAL FACEBOOK ACCOUNT', 'CONNECTED')
                """, accountId, workspaceId, "test_" + accountId);
        return accountId;
    }

    private static UUID createJob(Connection conn, UUID workspaceId, UUID accountId, String approval)
            throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        execute(conn, """
                INSERT INTO posts (id, workspace_id, topic, content, status, approval_status)
                VALUES (?, ?, ?, 'Integration test only - NEVER publish', 'READY', ?)
                """, postId, workspaceId, "TEST_FB_" + approval, approval);
        execute(conn, """
                INSERT INTO publishing_jobs
                  (id, post_id, social_account_id, status, attempt_count, scheduled_at)
                VALUES (?, ?, ?, 'PENDING', 0, NOW() - INTERVAL '1 minute')
                """, jobId, postId, accountId);
        return jobId;
    }

    /**
     * Regular Facebook claim: same filters as claim() in MOCK mode, restricted to one test job.
     */
    private static boolean claimRegularWithoutPublishing(Connection conn, UUID jobId) throws SQLException {
        String sql = """
                WITH picked AS (
                  SELECT j.id FROM publishing_jobs j
                  JOIN social_accounts a ON a.id = j.social_account_id
                  JOIN posts p ON p.id = j.post_id
                  WHERE (
                    (CAST(? AS BOOLEAN) = TRUE AND j.status='PENDING'
                      AND j.attempt_count=0 AND j.external_post_id IS NULL)
                    OR (CAST(? AS BOOLEAN) = FALSE AND j.status IN ('PENDING','FAILED'))
                  )
                    AND a.platform='FACEBOOK_PAGE' AND a.status='CONNECTED'
                    AND p.status IN ('READY','SCHEDULED')
                    AND p.approval_status='APPROVED'
                    AND NOT EXISTS (
                      SELECT 1 FROM facebook_bulk_run_jobs bj
                      WHERE bj.publishing_job_id = j.id
                    )
                    AND (
                      NOT EXISTS (SELECT 1 FROM campaign_jobs cj WHERE cj.publishing_job_id=j.id)
                      OR EXISTS (
                        SELECT 1 FROM campaign_jobs cj JOIN campaigns c ON c.id=cj.campaign_id
                        WHERE cj.publishing_job_id=j.id AND c.status='RUNNING'
                      )
                    )
                    AND COALESCE(j.scheduled_at,j.created_at)<=NOW()
                    AND (j.next_retry_at IS NULL OR j.next_retry_at<=NOW())
                    AND (CAST(? AS BOOLEAN)=FALSE OR j.created_at>=CAST(? AS TIMESTAMPTZ))
                    AND j.attempt_count < ?
                    AND (CAST(? AS BOOLEAN)=FALSE OR j.attempt_count=0)
                    AND (CAST(? AS UUID) IS NULL OR j.id=CAST(? AS UUID))
                    AND j.id = ?
                  ORDER BY COALESCE(j.next_retry_at,j.scheduled_at,j.created_at), j.created_at
                  FOR UPDATE OF j SKIP LOCKED LIMIT 1
                )
                UPDATE publishing_jobs j
                SET status='PROCESSING', attempt_count=j.attempt_count+1,
                    processing_started_at=NOW(), locked_until=NOW()+INTERVAL '10 minutes',
                    next_retry_at=NULL
                FROM picked WHERE j.id=picked.id
                RETURNING j.id
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, false); // automaticLive = false (MOCK-mode claim predicate)
            stmt.setBoolean(2, false);
            stmt.setBoolean(3, false);
            stmt.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
            stmt.setInt(5, 3);       // MAX_ATTEMPTS
            stmt.setBoolean(6, false); // isLive = false
            stmt.setNull(7, Types.OTHER);
            stmt.setNull(8, Types.OTHER);
            stmt.setObject(9, jobId); // limit test to its fixture
            return hasOneRow(stmt);
        }
    }

    /**
     * Bulk claim: same eligible criteria as claimBulk(), scoped to one fixture.
     */
    private static boolean claimBulkWithoutPublishing(Connection conn, UUID jobId) throws SQLException {
        String sql = """
                WITH picked AS (
                  SELECT j.id FROM publishing_jobs j
                  JOIN facebook_bulk_run_jobs bj ON bj.publishing_job_id=j.id
                  JOIN facebook_bulk_runs br ON br.id=bj.bulk_run_id
                  JOIN social_accounts a ON a.id=j.social_account_id
                  JOIN posts p ON p.id=j.post_id
                  WHERE br.status='RUNNING'
                    AND br.workspace_id=p.workspace_id
                    AND br.workspace_id=a.workspace_id
                    AND j.status='PENDING' AND j.attempt_count=0
                    AND j.external_post_id IS NULL
                    AND a.platform='FACEBOOK_PAGE' AND a.status='CONNECTED'
                    AND p.status IN ('READY','SCHEDULED')
                    AND p.approval_status='APPROVED'
                    AND NOT EXISTS (
                      SELECT 1 FROM campaign_jobs cj WHERE cj.publishing_job_id=j.id
                    )
                    AND NOT EXISTS (SELECT 1 FROM post_media m WHERE m.post_id=p.id)
                    AND COALESCE(j.scheduled_at,j.created_at)<=NOW()
                    AND (j.next_retry_at IS NULL OR j.next_retry_at<=NOW())
                    AND j.id = ?
                  ORDER BY COALESCE(j.scheduled_at,j.created_at), j.created_at
                  FOR UPDATE OF j SKIP LOCKED LIMIT 1
                )
                UPDATE publishing_jobs j
                SET status='PROCESSING', attempt_count=j.attempt_count+1,
                    processing_started_at=NOW(), locked_until=NOW()+INTERVAL '10 minutes',
                    next_retry_at=NULL
                FROM picked WHERE j.id=picked.id
                RETURNING j.id
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, jobId);
            return hasOneRow(stmt);
        }
    }

    private static boolean hasOneRow(PreparedStatement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) return false;
            assertFalse(rs.next(), "One fixture should claim no more than one job");
            return true;
        }
    }

    private static String jobStatus(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT status FROM publishing_jobs WHERE id=?")) {
            stmt.setObject(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private static int attemptCount(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT attempt_count FROM publishing_jobs WHERE id=?")) {
            stmt.setObject(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    @FunctionalInterface
    private interface DbTest {
        void run(Connection conn) throws Exception;
    }

    private static void withTestDatabase(DbTest test) throws Exception {
        String url = requiredEnv("TEST_DB_URL");
        String user = requiredEnv("TEST_DB_USERNAME");
        String password = requiredEnv("TEST_DB_PASSWORD");
        assertTrue(url.matches("^jdbc:postgresql://[^/?#]+/socialhub_test(?:\\?.*)?$"),
                "SAFETY BLOCK: TEST_DB_URL must target /socialhub_test");
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);
            try {
                assertEquals("socialhub_test", currentDatabase(conn),
                        "SAFETY BLOCK: refusing writes outside socialhub_test");
                test.run(conn);
            } finally {
                conn.rollback();
            }
        }
    }

    private static String currentDatabase(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT current_database()");
             ResultSet rs = stmt.executeQuery()) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static void execute(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) stmt.setObject(i + 1, args[i]);
            assertEquals(1, stmt.executeUpdate(), "Fixture SQL should affect one row");
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "Missing environment variable: " + name);
        assertFalse(value.isBlank(), "Empty environment variable: " + name);
        return value;
    }
}
