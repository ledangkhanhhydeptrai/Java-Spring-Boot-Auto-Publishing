package com.socialhub.publishingjob.service.implement;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL concurrency regression test for the Post row-lock protocol.
 * No Spring context, scheduler, Meta client, or real accounts.
 * NOTE: It tests the SQL locking protocol, not direct Spring Service method invocation.
 */
class PublishingJobConcurrencyIntegrationTest {

    @Test
    void creatingJobFirstPreventsContentMutation() throws Exception {
        Config config = config();
        Fixture f = createFixture(config);
        try (Connection creator = connect(config); Connection editor = connect(config)) {
            creator.setAutoCommit(false);
            editor.setAutoCommit(false);
            try {
                lockPost(creator, f.postId);
                assertEquals("APPROVED", approval(creator, f.postId));

                // Simulate update() waiting on the same Post lock. A short lock timeout
                // proves that it cannot inspect and edit the Post while create() holds it.
                execute(editor, "SET LOCAL lock_timeout = '300ms'");
                SQLException lockError = assertThrows(SQLException.class,
                        () -> lockPost(editor, f.postId));
                assertEquals("55P03", lockError.getSQLState(), "Expected PostgreSQL lock_not_available");
                editor.rollback(); // reset transaction after lock_timeout

                createJob(creator, f);
                creator.commit();

                // Once create() commits, update() can acquire the Post lock but
                // must reject changes because hasPublishingTasks(postId) is true.
                lockPost(editor, f.postId);
                assertTrue(hasPublishingTasks(editor, f.postId));
                assertEquals("APPROVED", approval(editor, f.postId));
                editor.rollback();
            } finally {
                creator.rollback();
                editor.rollback();
            }
        } finally {
            deleteFixture(config, f);
        }
    }

    @Test
    void editingPostFirstPreventsJobCreationWithStaleApproval() throws Exception {
        Config config = config();
        Fixture f = createFixture(config);
        try (Connection editor = connect(config); Connection creator = connect(config)) {
            editor.setAutoCommit(false);
            creator.setAutoCommit(false);
            try {
                lockPost(editor, f.postId);
                execute(editor, """
                        UPDATE posts SET content='Edited: requires reapproval',
                        approval_status='NOT_SUBMITTED', approved_by=NULL, approved_at=NULL
                        WHERE id=?
                        """, f.postId);

                execute(creator, "SET LOCAL lock_timeout = '300ms'");
                SQLException lockError = assertThrows(SQLException.class,
                        () -> lockPost(creator, f.postId));
                assertEquals("55P03", lockError.getSQLState());
                creator.rollback();

                editor.commit();
                lockPost(creator, f.postId);
                assertEquals("NOT_SUBMITTED", approval(creator, f.postId));
                assertFalse(hasPublishingTasks(creator, f.postId));
                // create() must reject before inserting any publishing_job.
                creator.rollback();
                assertEquals(0, jobCount(config, f.postId));
            } finally {
                editor.rollback();
                creator.rollback();
            }
        } finally {
            deleteFixture(config, f);
        }
    }

    private record Config(String url, String user, String password) {
    }

    private record Fixture(UUID workspaceId, UUID accountId, UUID postId, UUID jobId) {
    }

    private static Config config() {
        String url = required("TEST_DB_URL");
        assertTrue(url.matches("^jdbc:postgresql://[^/?#]+/socialhub_test(?:\\?.*)?$"),
                "SAFETY BLOCK: TEST_DB_URL must explicitly target /socialhub_test");
        return new Config(url, required("TEST_DB_USERNAME"), required("TEST_DB_PASSWORD"));
    }

    private static String required(String key) {
        String value = System.getenv(key);
        assertNotNull(value, "Missing " + key);
        assertFalse(value.isBlank(), "Empty " + key);
        return value;
    }

    private static Connection connect(Config config) throws SQLException {
        Connection c = DriverManager.getConnection(config.url, config.user, config.password);
        try (PreparedStatement s = c.prepareStatement("SELECT current_database()");
             ResultSet r = s.executeQuery()) {
            assertTrue(r.next());
            assertEquals("socialhub_test", r.getString(1), "SAFETY BLOCK: wrong database");
        }
        return c;
    }

    private static Fixture createFixture(Config config) throws SQLException {
        Fixture f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        try (Connection c = connect(config)) {
            c.setAutoCommit(false);
            try {
                execute(c, "INSERT INTO workspaces(id,name) VALUES (?,?)",
                        f.workspaceId, "TEST_CONCURRENCY_" + f.workspaceId);
                execute(c, """
                        INSERT INTO social_accounts(id,workspace_id,platform,external_account_id,display_name,status)
                        VALUES (?,?,'FACEBOOK_PAGE',?,'FAKE ACCOUNT - NO TOKEN','CONNECTED')
                        """, f.accountId, f.workspaceId, "test_" + f.accountId);
                execute(c, """
                        INSERT INTO posts(id,workspace_id,topic,content,status,approval_status)
                        VALUES (?,?,'TEST_CONCURRENCY','No external publishing','READY','APPROVED')
                        """, f.postId, f.workspaceId);
                c.commit(); // other connections must see the fixture to exercise locks
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        return f;
    }

    private static void deleteFixture(Config config, Fixture f) throws SQLException {
        try (Connection c = connect(config)) {
            c.setAutoCommit(false);
            try {
                execute(c, "DELETE FROM publishing_jobs WHERE id=?", f.jobId);
                execute(c, "DELETE FROM posts WHERE id=?", f.postId);
                execute(c, "DELETE FROM social_accounts WHERE id=?", f.accountId);
                execute(c, "DELETE FROM workspaces WHERE id=?", f.workspaceId);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static void lockPost(Connection c, UUID id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM posts WHERE id=? FOR UPDATE")) {
            s.setObject(1, id);
            try (ResultSet r = s.executeQuery()) {
                assertTrue(r.next());
            }
        }
    }

    private static String approval(Connection c, UUID id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT approval_status FROM posts WHERE id=?")) {
            s.setObject(1, id);
            try (ResultSet r = s.executeQuery()) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }
    }

    private static boolean hasPublishingTasks(Connection c, UUID id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                SELECT EXISTS(SELECT 1 FROM publishing_jobs WHERE post_id=?)
                    OR EXISTS(SELECT 1 FROM group_publishing_tasks WHERE post_id=?)
                """)) {
            s.setObject(1, id);
            s.setObject(2, id);
            try (ResultSet r = s.executeQuery()) {
                assertTrue(r.next());
                return r.getBoolean(1);
            }
        }
    }

    private static void createJob(Connection c, Fixture f) throws SQLException {
        execute(c, """
                INSERT INTO publishing_jobs(id,post_id,social_account_id,status,attempt_count,scheduled_at)
                VALUES (?,?,?,'PENDING',0,NOW()+INTERVAL '1 day')
                """, f.jobId, f.postId, f.accountId);
    }

    private static int jobCount(Config config, UUID postId) throws SQLException {
        try (Connection c = connect(config);
             PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM publishing_jobs WHERE post_id=?")) {
            s.setObject(1, postId);
            try (ResultSet r = s.executeQuery()) {
                assertTrue(r.next());
                return r.getInt(1);
            }
        }
    }

    private static int execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            return s.executeUpdate();
        }
    }
}
