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
 * DESTRUCTIVE one-shot local E2E test. Never run against a real database or Meta.
 */
class FacebookNeedsReviewE2ETest {
    private static final UUID JOB = UUID.fromString("900cf1c1-1054-4538-b80a-64504a935c58");
    private static final UUID POST = UUID.fromString("91c30e21-d136-4771-8fdb-e274657d4396");
    private static final UUID ACCOUNT = UUID.fromString("5e617f23-24a6-4d14-9c7d-9ac211959836");
    private static final String PAGE = "999000111222";
    private static final String DB_URL = "jdbc:postgresql://localhost:5433/socialhub_test";
    private static final String MOCK = "http://127.0.0.1:9090";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void unknownOutcomeRequiresManualReview() throws Exception {
        assertEquals("YES", System.getenv("SOCIALHUB_RUN_MOCK_E2E"), "Explicit one-shot opt-in required");
        assertEquals(DB_URL, env("TEST_DB_URL"), "Only isolated database is allowed");
        assertEquals("mock_test_token", env("META_PAGE_TOKEN_" + PAGE));
        var datasource = new DriverManagerDataSource(DB_URL, env("TEST_DB_USERNAME"), env("TEST_DB_PASSWORD"));
        var db = new JdbcTemplate(datasource);
        assertEquals("socialhub_test", db.queryForObject("SELECT current_database()", String.class));

        JsonNode health = get(MOCK + "/health");
        assertEquals("UP", health.path("status").asText());
        assertEquals("MOCK", health.path("mode").asText());
        assertEquals("FAIL_AFTER_ACCEPT_ONCE", health.path("fault").asText(), "Wrong mock server: stop before posting");
        assertEquals("127.0.0.1", health.path("host").asText());
        assertEquals(9090, health.path("port").asInt());
        assertEquals(0, get(MOCK + "/requests").path("count").asInt(-1), "Use freshly started one-shot failure mock");

        List<UUID> candidates = db.query("""
                SELECT j.id FROM publishing_jobs j
                JOIN social_accounts a ON a.id=j.social_account_id
                JOIN posts p ON p.id=j.post_id
                WHERE j.status IN ('PENDING', 'FAILED') AND j.attempt_count < 3
                  AND a.platform='FACEBOOK_PAGE' AND a.status='CONNECTED'
                  AND p.status IN ('READY','SCHEDULED') AND p.approval_status='APPROVED'
                  AND COALESCE(j.scheduled_at,j.created_at) <= NOW()
                  AND (j.next_retry_at IS NULL OR j.next_retry_at <= NOW())
                  AND NOT EXISTS (SELECT 1 FROM facebook_bulk_run_jobs bj WHERE bj.publishing_job_id=j.id)
                  AND (NOT EXISTS (SELECT 1 FROM campaign_jobs cj WHERE cj.publishing_job_id=j.id)
                    OR EXISTS (SELECT 1 FROM campaign_jobs cj JOIN campaigns c ON c.id=cj.campaign_id
                               WHERE cj.publishing_job_id=j.id AND c.status='RUNNING'))
                """, (rs, i) -> rs.getObject(1, UUID.class));
        assertEquals(List.of(JOB), candidates, "Stop: a different or multiple jobs could be claimed");
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM publishing_jobs WHERE status='PROCESSING'", Integer.class),
                "Stop: some worker is already processing");

        Map<String, Object> row = db.queryForMap("""
                SELECT j.post_id, j.social_account_id, j.status, j.attempt_count, j.external_post_id,
                       p.status AS post_status, p.approval_status, p.content,
                       a.platform, a.status AS account_status, a.external_account_id
                FROM publishing_jobs j JOIN posts p ON p.id=j.post_id
                JOIN social_accounts a ON a.id=j.social_account_id
                WHERE j.id=?
                """, JOB);
        assertEquals(POST, row.get("post_id"));
        assertEquals(ACCOUNT, row.get("social_account_id"));
        assertEquals("PENDING", row.get("status"));
        assertEquals(0, ((Number) row.get("attempt_count")).intValue());
        assertNull(row.get("external_post_id"));
        assertEquals("READY", row.get("post_status"));
        assertEquals("APPROVED", row.get("approval_status"));
        assertEquals("FACEBOOK_PAGE", row.get("platform"));
        assertEquals("CONNECTED", row.get("account_status"));
        assertEquals(PAGE, row.get("external_account_id"));
        assertNotNull(row.get("content"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM post_media WHERE post_id=?", Integer.class, POST));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM publishing_attempts WHERE publishing_job_id=?", Integer.class, JOB));

        var worker = new FanpageQueueProcessor(db, JSON,
                new TransactionTemplate(new DataSourceTransactionManager(datasource)), null);
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

        Map<String, Object> after = db.queryForMap("SELECT status,attempt_count,external_post_id,last_error FROM publishing_jobs WHERE id=?", JOB);
        assertEquals("NEEDS_REVIEW", after.get("status"), "Expected uncertain outcome: " + after.get("last_error"));
        assertEquals(1, ((Number) after.get("attempt_count")).intValue());
        assertNull(after.get("external_post_id"));
        assertEquals("UNKNOWN", db.queryForObject("""
                SELECT status FROM publishing_attempts WHERE publishing_job_id=? AND attempt_number=1
                """, String.class, JOB));
        assertEquals(1, get(MOCK + "/requests").path("count").asInt(-1));
        worker.processBatch();
        assertEquals(1, get(MOCK + "/requests").path("count").asInt(-1), "Worker must not automatically retry uncertain outcome");
        System.out.println("[NEEDS_REVIEW PASS] One mock failure, Job NEEDS_REVIEW, Attempt UNKNOWN, no automatic retry");
    }

    private static String env(String key) {
        String value = System.getenv(key);
        assertNotNull(value, "Missing environment variable: " + key);
        assertFalse(value.isBlank(), "Blank environment variable: " + key);
        return value;
    }

    private static JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> result = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode(), "Mock server unavailable: " + url);
        return JSON.readTree(result.body());
    }
}
