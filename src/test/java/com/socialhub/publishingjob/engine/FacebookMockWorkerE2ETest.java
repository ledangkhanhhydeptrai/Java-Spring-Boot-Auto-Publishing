package com.socialhub.publishingjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ONE-SHOT, stateful, local-only E2E smoke test.
 * IMPORTANT: This test intentionally WRITES to socialhub_test and sends one POST
 * to a localhost mock server. Run it alone, after the isolated seed SQL.
 * It NEVER contacts a real Meta endpoint or enables the application scheduler.
 */
class FacebookMockWorkerE2ETest {
    private static final UUID JOB_ID = UUID.fromString("e2000000-0000-4000-8000-000000000005");
    private static final UUID POST_ID = UUID.fromString("e2000000-0000-4000-8000-000000000004");
    private static final UUID ACCOUNT_ID = UUID.fromString("e2000000-0000-4000-8000-000000000003");
    private static final String PAGE_ID = "999000111222";
    private static final String MOCK_BASE = "http://127.0.0.1:9090";
    private static final String MESSAGE =
            "SocialHub isolated E2E mock publication. Not a real Facebook post.";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void publishesExactlyOnceToLocalMock() throws Exception {
        // Explicit opt-in prevents ordinary `mvn test` from issuing any POST.
        assertEquals("YES", System.getenv("SOCIALHUB_RUN_MOCK_E2E"),
                "Set SOCIALHUB_RUN_MOCK_E2E=YES explicitly before running this one-shot test");

        String url = envOr("TEST_DB_URL", "jdbc:postgresql://localhost:5433/socialhub_test");
        assertEquals("jdbc:postgresql://localhost:5433/socialhub_test", url,
                "Safety stop: TEST_DB_URL must point to isolated localhost:5433/socialhub_test");
        String username = requiredEnv("TEST_DB_USERNAME");
        String password = requiredEnv("TEST_DB_PASSWORD");
        assertEquals("mock_test_token", requiredEnv("META_PAGE_TOKEN_" + PAGE_ID),
                "Safety stop: only the fake Facebook Page token is permitted");

        DataSource dataSource = new DriverManagerDataSource(url, username, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Every precondition below is checked BEFORE worker is constructed or any POST is sent.
        assertEquals("socialhub_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(1, number(jdbc, "SELECT COUNT(*) FROM publishing_jobs"),
                "Safety stop: test DB must contain exactly one publishing job");
        assertEquals(1, number(jdbc, "SELECT COUNT(*) FROM posts"));
        assertEquals(1, number(jdbc, "SELECT COUNT(*) FROM social_accounts"));
        assertEquals(0, number(jdbc, "SELECT COUNT(*) FROM publishing_attempts"));
        assertEquals(0, number(jdbc, "SELECT COUNT(*) FROM post_media"));
        assertEquals(0, number(jdbc, "SELECT COUNT(*) FROM campaign_jobs"));
        assertEquals(0, number(jdbc, "SELECT COUNT(*) FROM facebook_bulk_run_jobs"));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT j.id, j.post_id, j.social_account_id, j.status, j.attempt_count,
                       j.external_post_id, p.status AS post_status, p.approval_status,
                       p.content, a.platform, a.status AS account_status, a.external_account_id,
                       (COALESCE(j.scheduled_at, j.created_at) <= NOW()) AS due_now
                FROM publishing_jobs j
                JOIN posts p ON p.id = j.post_id
                JOIN social_accounts a ON a.id = j.social_account_id
                WHERE j.id = ?
                """, JOB_ID);
        assertEquals(JOB_ID, row.get("id"));
        assertEquals(POST_ID, row.get("post_id"));
        assertEquals(ACCOUNT_ID, row.get("social_account_id"));
        assertEquals("PENDING", row.get("status"));
        assertEquals(0, ((Number) row.get("attempt_count")).intValue());
        assertNull(row.get("external_post_id"));
        assertEquals("READY", row.get("post_status"));
        assertEquals("APPROVED", row.get("approval_status"));
        assertEquals(MESSAGE, row.get("content"));
        assertEquals("FACEBOOK_PAGE", row.get("platform"));
        assertEquals("CONNECTED", row.get("account_status"));
        assertEquals(PAGE_ID, row.get("external_account_id"));
        assertEquals(Boolean.TRUE, row.get("due_now"));

        JsonNode health = getJson(MOCK_BASE + "/health");
        assertEquals("UP", health.path("status").asText());
        assertEquals("MOCK", health.path("mode").asText());
        assertEquals("127.0.0.1", health.path("host").asText());
        assertEquals(9090, health.path("port").asInt());
        assertEquals(0, getJson(MOCK_BASE + "/requests").path("count").asInt(-1),
                "Safety stop: restart localhost Mock Server to clear prior requests");

        var worker = new FanpageQueueProcessor(jdbc, JSON,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), null);
        ReflectionTestUtils.setField(worker, "graphVersion", "v23.0");
        ReflectionTestUtils.setField(worker, "batchSize", 1);
        ReflectionTestUtils.setField(worker, "multiJobEnabled", false);
        ReflectionTestUtils.setField(worker, "multiJobNotBefore", "");
        ReflectionTestUtils.setField(worker, "metaBaseUrl", MOCK_BASE);
        ReflectionTestUtils.setField(worker, "publishingMode", "MOCK");
        ReflectionTestUtils.setField(worker, "liveJobId", "");
        ReflectionTestUtils.setField(worker, "bulkEnabled", false);
        ReflectionTestUtils.setField(worker, "maxImagesPerPost", 10);

        // Real worker claim + one real HTTP request to the LOCAL mock, not Meta.
        worker.processBatch();

        Map<String, Object> after = jdbc.queryForMap("""
                SELECT status, attempt_count, external_post_id, published_at, last_error
                FROM publishing_jobs WHERE id = ?
                """, JOB_ID);
        assertEquals("PUBLISHED", after.get("status"), "Worker failure: " + after.get("last_error"));
        assertEquals(1, ((Number) after.get("attempt_count")).intValue());
        assertNotNull(after.get("published_at"));
        assertNotNull(after.get("external_post_id"));
        assertEquals(1, number(jdbc, "SELECT COUNT(*) FROM publishing_attempts"));
        assertEquals("SUCCESS", jdbc.queryForObject("""
                SELECT status FROM publishing_attempts
                WHERE publishing_job_id = ? AND attempt_number = 1
                """, String.class, JOB_ID));

        JsonNode requests = getJson(MOCK_BASE + "/requests");
        assertEquals(1, requests.path("count").asInt(-1), "Expected exactly one POST to local mock");
        JsonNode request = requests.path("requests").get(0);
        assertEquals(PAGE_ID, request.path("page_id").asText());
        assertEquals(MESSAGE, request.path("message").asText());
        assertEquals(after.get("external_post_id"), request.path("mock_post_id").asText());

        // No second publish on subsequent polling.
        worker.processBatch();
        assertEquals(1, getJson(MOCK_BASE + "/requests").path("count").asInt(-1));
        assertEquals(1, number(jdbc, "SELECT COUNT(*) FROM publishing_attempts"));
        System.out.println("[MOCK E2E PASS] One PUBLISHED job, one SUCCESS attempt, one local POST, zero duplicates");
    }

    private static int number(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        assertTrue(value != null && !value.isBlank(), "Missing environment variable: " + key);
        return value;
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(4)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Mock server unavailable: " + url);
        return JSON.readTree(response.body());
    }
}
