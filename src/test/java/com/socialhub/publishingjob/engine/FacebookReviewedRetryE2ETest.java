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
 * One-shot, state-changing LOCAL MOCK E2E test: Reviewed Attempt #1 -> Successful Attempt #2.
 */
class FacebookReviewedRetryE2ETest {
    private static final UUID JOB = UUID.fromString("900cf1c1-1054-4538-b80a-64504a935c58");
    private static final UUID POST = UUID.fromString("91c30e21-d136-4771-8fdb-e274657d4396");
    private static final UUID ACCOUNT = UUID.fromString("5e617f23-24a6-4d14-9c7d-9ac211959836");
    private static final String PAGE = "999000111222";
    private static final String DB_URL = "jdbc:postgresql://localhost:5433/socialhub_test";
    private static final String MOCK = "http://127.0.0.1:9090";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void reviewedAttemptOneCanPublishExactlyOnceOnAttemptTwo() throws Exception {
        assertEquals("YES", System.getenv("SOCIALHUB_RUN_MOCK_E2E"), "Explicit test opt-in required");
        assertEquals(DB_URL, requiredEnv("TEST_DB_URL"), "Only socialhub_test may be used");
        assertEquals("mock_test_token", requiredEnv("META_PAGE_TOKEN_" + PAGE));

        var ds = new DriverManagerDataSource(DB_URL, requiredEnv("TEST_DB_USERNAME"), requiredEnv("TEST_DB_PASSWORD"));
        var db = new JdbcTemplate(ds);
        assertEquals("socialhub_test", db.queryForObject("SELECT current_database()", String.class));

        JsonNode health = get(MOCK + "/health");
        assertEquals("UP", health.path("status").asText());
        assertEquals("MOCK", health.path("mode").asText());
        assertEquals("127.0.0.1", health.path("host").asText());
        assertEquals(9090, health.path("port").asInt());
        assertFalse(health.has("fault"), "Stop: failure-mode server is still running; start facebook_mock_server.py");
        int baseline = get(MOCK + "/requests").path("count").asInt(-1);
        assertEquals(0, baseline, "Stop: expected a freshly started success mock with 0 requests");

        // Mirrors the MOCK claim's eligibility gate; only the intended job may be picked.
        List<UUID> candidates = db.query("""
                SELECT j.id FROM publishing_jobs j
                JOIN social_accounts a ON a.id=j.social_account_id
                JOIN posts p ON p.id=j.post_id
                WHERE j.status IN ('PENDING', 'FAILED')
                  AND j.attempt_count < 3
                  AND a.platform='FACEBOOK_PAGE' AND a.status='CONNECTED'
                  AND p.status IN ('READY','SCHEDULED') AND p.approval_status='APPROVED'
                  AND COALESCE(j.scheduled_at,j.created_at) <= NOW()
                  AND (j.next_retry_at IS NULL OR j.next_retry_at <= NOW())
                  AND NOT EXISTS (SELECT 1 FROM facebook_bulk_run_jobs bj WHERE bj.publishing_job_id=j.id)
                  AND (NOT EXISTS (SELECT 1 FROM campaign_jobs cj WHERE cj.publishing_job_id=j.id)
                       OR EXISTS (SELECT 1 FROM campaign_jobs cj JOIN campaigns c ON c.id=cj.campaign_id
                                  WHERE cj.publishing_job_id=j.id AND c.status='RUNNING'))
                  AND ((j.attempt_count=0 AND j.external_post_id IS NULL
                        AND j.published_at IS NULL AND j.published_url IS NULL)
                     OR (j.status='PENDING' AND j.attempt_count BETWEEN 1 AND 2
                         AND j.external_post_id IS NULL AND j.published_at IS NULL
                         AND j.published_url IS NULL AND EXISTS (
                             SELECT 1 FROM publishing_reviews r
                             WHERE r.publishing_job_id=j.id AND r.attempt_number=j.attempt_count
                               AND r.decision='CONFIRM_NOT_PUBLISHED'
                               AND NULLIF(BTRIM(r.evidence),'') IS NOT NULL
                               AND NULLIF(BTRIM(r.note),'') IS NOT NULL)))
                """, (rs, idx) -> rs.getObject(1, UUID.class));
        assertEquals(List.of(JOB), candidates, "Safety stop: another job is eligible for claim");
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM publishing_jobs WHERE status='PROCESSING'", Integer.class));

        Map<String, Object> before = db.queryForMap("""
                SELECT j.post_id, j.social_account_id, j.status, j.attempt_count,
                       j.external_post_id, j.published_at, j.published_url,
                       p.status AS post_status, p.approval_status, p.content,
                       a.platform, a.status AS account_status, a.external_account_id
                FROM publishing_jobs j JOIN posts p ON j.post_id=p.id
                JOIN social_accounts a ON j.social_account_id=a.id WHERE j.id=?
                """, JOB);
        assertEquals(POST, before.get("post_id"));
        assertEquals(ACCOUNT, before.get("social_account_id"));
        assertEquals("PENDING", before.get("status"));
        assertEquals(1, ((Number) before.get("attempt_count")).intValue());
        assertNull(before.get("external_post_id"));
        assertNull(before.get("published_at"));
        assertNull(before.get("published_url"));
        assertEquals("READY", before.get("post_status"));
        assertEquals("APPROVED", before.get("approval_status"));
        assertEquals("FACEBOOK_PAGE", before.get("platform"));
        assertEquals("CONNECTED", before.get("account_status"));
        assertEquals(PAGE, before.get("external_account_id"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM post_media WHERE post_id=?", Integer.class, POST));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM publishing_attempts WHERE publishing_job_id=?", Integer.class, JOB));
        assertEquals("UNKNOWN", db.queryForObject("""
                SELECT status FROM publishing_attempts WHERE publishing_job_id=? AND attempt_number=1
                """, String.class, JOB));
        assertEquals("CONFIRM_NOT_PUBLISHED", db.queryForObject("""
                SELECT decision FROM publishing_reviews WHERE publishing_job_id=? AND attempt_number=1
                """, String.class, JOB));

        var worker = new FanpageQueueProcessor(db, JSON,
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

        Map<String, Object> after = db.queryForMap("""
                SELECT status, attempt_count, external_post_id, published_at, last_error
                FROM publishing_jobs WHERE id=?
                """, JOB);
        assertEquals("PUBLISHED", after.get("status"), "Worker error: " + after.get("last_error"));
        assertEquals(2, ((Number) after.get("attempt_count")).intValue());
        assertNotNull(after.get("external_post_id"));
        assertNotNull(after.get("published_at"));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM publishing_attempts WHERE publishing_job_id=?", Integer.class, JOB));
        assertEquals("UNKNOWN", db.queryForObject("SELECT status FROM publishing_attempts WHERE publishing_job_id=? AND attempt_number=1", String.class, JOB));
        assertEquals("SUCCESS", db.queryForObject("SELECT status FROM publishing_attempts WHERE publishing_job_id=? AND attempt_number=2", String.class, JOB));
        JsonNode requests = get(MOCK + "/requests");
        assertEquals(baseline + 1, requests.path("count").asInt(-1));
        JsonNode request = requests.path("requests").get(baseline);
        assertNotNull(request);
        assertEquals(PAGE, request.path("page_id").asText());
        assertEquals(before.get("content"), request.path("message").asText());
        assertEquals(after.get("external_post_id"), request.path("mock_post_id").asText());

        worker.processBatch();
        assertEquals(baseline + 1, get(MOCK + "/requests").path("count").asInt(-1), "Duplicate mock publication");
        System.out.println("[RETRY MOCK E2E PASS] Reviewed attempt #1 UNKNOWN, attempt #2 SUCCESS, exactly one new request");
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        assertNotNull(value, "Missing environment variable: " + key);
        assertFalse(value.isBlank(), "Blank environment variable: " + key);
        return value;
    }

    private static JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Mock server unavailable: " + url);
        return JSON.readTree(response.body());
    }
}
