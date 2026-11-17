package com.socialhub.publishingjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One-time, state-changing E2E test. Run ONLY against local socialhub_test and local Mock Server.
 */
class FacebookTargetedMockE2ETest {
    private static final UUID JOB = UUID.fromString("6cf818bb-bd71-443f-b042-7ae5fea9f8b7");
    private static final UUID POST = UUID.fromString("3982bb95-9430-401b-9da8-4aff1371f7aa");
    private static final UUID ACCOUNT = UUID.fromString("5e617f23-24a6-4d14-9c7d-9ac211959836");
    private static final String PAGE = "999000111222";
    private static final String DB_URL = "jdbc:postgresql://localhost:5433/socialhub_test";
    private static final String MOCK = "http://127.0.0.1:9090";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void publishOnlyTheApprovedTargetJob() throws Exception {
        assertEquals("YES", System.getenv("SOCIALHUB_RUN_MOCK_E2E"),
                "Explicit opt-in required: SOCIALHUB_RUN_MOCK_E2E=YES");
        assertEquals(DB_URL, env("TEST_DB_URL"), "Only exact isolated test database is allowed");
        assertEquals("mock_test_token", env("META_PAGE_TOKEN_" + PAGE));
        String username = env("TEST_DB_USERNAME");
        String password = env("TEST_DB_PASSWORD");

        var ds = new DriverManagerDataSource(DB_URL, username, password);
        var jdbc = new JdbcTemplate(ds);
        assertEquals("socialhub_test", jdbc.queryForObject("SELECT current_database()", String.class));

        // No other candidate may be claimable by FanpageQueueProcessor.claim().
        // This mirrors its MOCK claim filters; fail closed if a second job could be claimed.
        List<UUID> candidates = jdbc.query("""
                SELECT j.id
                FROM publishing_jobs j
                JOIN social_accounts a ON a.id=j.social_account_id
                JOIN posts p ON p.id=j.post_id
                WHERE j.status IN ('PENDING', 'FAILED')
                  AND a.platform='FACEBOOK_PAGE' AND a.status='CONNECTED'
                  AND p.status IN ('READY','SCHEDULED')
                  AND p.approval_status='APPROVED'
                  AND NOT EXISTS (SELECT 1 FROM facebook_bulk_run_jobs bj WHERE bj.publishing_job_id=j.id)
                  AND (NOT EXISTS (SELECT 1 FROM campaign_jobs cj WHERE cj.publishing_job_id=j.id)
                       OR EXISTS (SELECT 1 FROM campaign_jobs cj JOIN campaigns c ON c.id=cj.campaign_id
                                  WHERE cj.publishing_job_id=j.id AND c.status='RUNNING'))
                  AND COALESCE(j.scheduled_at,j.created_at) <= NOW()
                  AND (j.next_retry_at IS NULL OR j.next_retry_at <= NOW())
                  AND j.attempt_count < 3
                """, (rs, index) -> rs.getObject(1, UUID.class));
        assertEquals(List.of(JOB), candidates,
                "Safety stop: a different or multiple Facebook jobs are claimable. Do not start worker.");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM publishing_jobs WHERE status='PROCESSING'", Integer.class),
                "Safety stop: another worker appears to be processing a job");

        Map<String, Object> before = jdbc.queryForMap("""
                SELECT j.id, j.post_id, j.social_account_id, j.status, j.attempt_count,
                       j.external_post_id, p.status AS post_status, p.approval_status,
                       p.content, a.platform, a.status AS account_status, a.external_account_id,
                       (COALESCE(j.scheduled_at, j.created_at) <= NOW()) AS due_now
                FROM publishing_jobs j
                JOIN posts p ON p.id=j.post_id
                JOIN social_accounts a ON a.id=j.social_account_id
                WHERE j.id=?
                """, JOB);
        assertEquals(JOB, before.get("id"));
        assertEquals(POST, before.get("post_id"));
        assertEquals(ACCOUNT, before.get("social_account_id"));
        assertEquals("PENDING", before.get("status"));
        assertEquals(0, ((Number) before.get("attempt_count")).intValue());
        assertNull(before.get("external_post_id"));
        assertEquals("READY", before.get("post_status"));
        assertEquals("APPROVED", before.get("approval_status"));
        assertEquals("FACEBOOK_PAGE", before.get("platform"));
        assertEquals("CONNECTED", before.get("account_status"));
        assertEquals(PAGE, before.get("external_account_id"));
        assertEquals(true, before.get("due_now"));
        String expectedMessage = (String) before.get("content");
        assertNotNull(expectedMessage);
        assertFalse(expectedMessage.isBlank());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM post_media WHERE post_id=?", Integer.class, POST),
                "This harness tests text-only publishing");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM publishing_attempts WHERE publishing_job_id=?", Integer.class, JOB));

        JsonNode health = get(MOCK + "/health");
        assertEquals("UP", health.path("status").asText());
        assertEquals("MOCK", health.path("mode").asText());
        assertEquals("127.0.0.1", health.path("host").asText());
        assertEquals(9090, health.path("port").asInt());
        int baseline = get(MOCK + "/requests").path("count").asInt(-1);
        assertEquals(1, baseline, "Safety stop: expected one previous local mock request");

        var worker = new FanpageQueueProcessor(jdbc, JSON,
                new TransactionTemplate(new DataSourceTransactionManager(ds)), null);
        ReflectionTestUtils.setField(worker, "graphVersion", "v23.0");
        ReflectionTestUtils.setField(worker, "batchSize", 1);
        ReflectionTestUtils.setField(worker, "multiJobEnabled", false);
        ReflectionTestUtils.setField(worker, "multiJobNotBefore", "");
        ReflectionTestUtils.setField(worker, "metaBaseUrl", MOCK);
        ReflectionTestUtils.setField(worker, "publishingMode", "MOCK");
        ReflectionTestUtils.setField(worker, "liveJobId", "");
        ReflectionTestUtils.setField(worker, "bulkEnabled", false);
        ReflectionTestUtils.setField(worker, "maxImagesPerPost", 10);

        worker.processBatch();

        Map<String, Object> after = jdbc.queryForMap("""
                SELECT status, attempt_count, external_post_id, published_at, last_error
                FROM publishing_jobs WHERE id=?
                """, JOB);
        assertEquals("PUBLISHED", after.get("status"), "Worker failed: " + after.get("last_error"));
        assertEquals(1, ((Number) after.get("attempt_count")).intValue());
        assertNotNull(after.get("external_post_id"));
        assertNotNull(after.get("published_at"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM publishing_attempts WHERE publishing_job_id=?", Integer.class, JOB));
        assertEquals("SUCCESS", jdbc.queryForObject("""
                SELECT status FROM publishing_attempts
                WHERE publishing_job_id=? AND attempt_number=1
                """, String.class, JOB));

        JsonNode requests = get(MOCK + "/requests");
        assertEquals(baseline + 1, requests.path("count").asInt(-1));
        JsonNode last = requests.path("requests").get(baseline);
        assertNotNull(last);
        assertEquals(PAGE, last.path("page_id").asText());
        assertEquals(expectedMessage, last.path("message").asText());
        assertEquals((String) after.get("external_post_id"), last.path("mock_post_id").asText());

        worker.processBatch();
        assertEquals(baseline + 1, get(MOCK + "/requests").path("count").asInt(-1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM publishing_attempts WHERE publishing_job_id=?", Integer.class, JOB));
        System.out.println("[MOCK E2E PASS] Target job PUBLISHED, one SUCCESS attempt, +1 local request, no duplicate");
    }

    private static String env(String key) {
        String value = System.getenv(key);
        assertNotNull(value, "Missing environment variable: " + key);
        assertFalse(value.isBlank(), "Blank environment variable: " + key);
        return value;
    }

    private static JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Mock server unavailable: " + url);
        return JSON.readTree(response.body());
    }
}
