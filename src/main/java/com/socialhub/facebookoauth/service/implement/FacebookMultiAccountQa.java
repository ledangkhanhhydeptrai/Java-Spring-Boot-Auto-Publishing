package com.socialhub.facebookoauth.service.implement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.common.ApiException;
import com.socialhub.publishingjob.engine.FanpageQueueProcessor;
import com.socialhub.publishingjob.bulk.service.FacebookBulkRunService;
import com.socialhub.publishingjob.bulk.service.WorkspaceBulkPermissionService;

import java.time.OffsetDateTime;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Standalone QA harness. Never launches Spring Boot or calls a provider.
 */
public final class FacebookMultiAccountQa {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private int passed;
    private int failed;

    private FacebookMultiAccountQa(DriverManagerDataSource ds) {
        db = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    public static void main(String[] args) throws Exception {
        String url = System.getenv("SH_QA_DB_URL");
        String password = System.getenv("SH_QA_DB_PASSWORD");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/socialhub_multi_account_qa")
                || password == null || password.length() < 24)
            throw new IllegalStateException("Run only with Run-FacebookMultiAccountQa.ps1 and its isolated database.");
        var ds = new DriverManagerDataSource(url, "qa_runner", password);
        var qa = new FacebookMultiAccountQa(ds);
        eq("socialhub_multi_account_qa", qa.db.queryForObject("SELECT current_database()", String.class), "database name");
        eq(0L, qa.count("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"), "database must be empty");
        var flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        flyway.migrate();
        eq("41", flyway.info().current().getVersion().getVersion(), "expected schema V41 from source (8)");
        System.out.println("[PASS] Flyway V1-V41 on isolated PostgreSQL");
        qa.test("Actual bulk claim: four media types, scoped run, mixed/media/overdue/foreign run rejected", qa::pilotClaim);
        qa.test("Actual bulk claim: disabled video/reel flags and missing pilot scope", qa::pilotDisabled);
        qa.test("Actual bulk start guard, persisted run jobs and reschedule", qa::pilotStart);
        System.out.printf("SUMMARY: PASS=%d FAIL=%d (plus Flyway setup)%n", qa.passed, qa.failed);
        if (qa.failed != 0) System.exit(1);
    }

    @FunctionalInterface
    interface Checked {
        void run() throws Exception;
    }

    private void test(String name, Checked action) throws Exception {
        // Dedicated disposable DB only; never execute against the application database.
        db.execute("TRUNCATE TABLE workspaces CASCADE");
        long start = System.nanoTime();
        try {
            action.run();
            passed++;
            System.out.printf("[PASS] %s (%d ms)%n", name, Duration.ofNanos(System.nanoTime() - start).toMillis());
        } catch (Exception | AssertionError e) {
            failed++;
            System.err.printf("[FAIL] %s: %s%n", name, e);
            e.printStackTrace(System.err);
        }
    }

    private static void eq(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
    }

    private static void require(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(db.queryForObject(sql, Long.class, args));
    }

    private UUID workspace() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO workspaces(id,name) VALUES (?,?)", id, "Isolated QA " + id);
        return id;
    }

    private String identity(int i) {
        return String.format(Locale.ROOT, "qa-%06d", i);
    }

    private void seed(UUID w, int n) {
        db.update("""
                INSERT INTO facebook_authorized_identities(workspace_id,facebook_user_id,display_name,user_token_encrypted,
                    token_expires_at,last_authorized_at,health_status,last_checked_at)
                SELECT ?, 'qa-' || lpad(i::text,6,'0'), 'QA Facebook ' || i, 'FAKE-NOT-A-TOKEN',
                    now()+interval '1 day',now(),'HEALTHY',now() FROM generate_series(1,?) AS s(i)
                """, w, n);
    }

    private final class FakeSync extends FacebookIdentityService {
        final Set<String> failures = new HashSet<>();
        final Map<String, Integer> calls = new ConcurrentHashMap<>();

        FakeSync() {
            super(FacebookMultiAccountQa.this.db, FacebookMultiAccountQa.this.tx, null, new ObjectMapper());
        }

        @Override
        public SyncResult sync(UUID w, String id) {
            calls.merge(w + ":" + id, 1, Integer::sum);
            if (failures.contains(id)) throw new IllegalStateException("simulated provider failure");
            return new SyncResult(1, 1, 0, 0);
        }
    }

    private FacebookIdentityService realIdentity() {
        // No token storage: health test uses only expired tokens; disconnect is SQL-only.
        return new FacebookIdentityService(db, tx, null, new ObjectMapper());
    }

    private FacebookIdentitySyncQueue queue(FacebookIdentityService service) {
        return new FacebookIdentitySyncQueue(db, tx, service);
    }

    private void ageFinished(UUID run) {
        // Accelerate tests only; production still enforces 2s spacing and 60s after failure.
        db.update("UPDATE facebook_identity_sync_items SET finished_at=now()-interval '61 seconds' WHERE run_id=? AND finished_at > now()-interval '61 seconds'", run);
    }

    private FacebookIdentitySyncQueue.Run latest(FacebookIdentitySyncQueue q, UUID w) {
        return Objects.requireNonNull(q.latest(w).run());
    }

    private void load(int n) {
        UUID w = workspace();
        seed(w, n);
        var fake = new FakeSync();
        var q = queue(fake);
        eq(null, q.latest(w).run(), "empty initial queue");
        var run = q.start(w, null);
        eq((long) n, run.total(), "enqueued count");
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            q.processOne();
            ageFinished(run.id());
        }
        var end = latest(q, w);
        eq("COMPLETED", end.status(), "completed status");
        eq((long) n, end.succeeded(), "successes");
        eq(0L, end.failed(), "failures");
        eq(0L, end.pending(), "pending");
        eq(0L, end.running(), "running");
        eq((long) n, end.pages(), "simulated saved page count");
        eq(n, fake.calls.size(), "distinct identities");
        require(fake.calls.values().stream().allMatch(v -> v == 1), "exactly one fake sync per identity");
        eq(0L, count("SELECT count(*) FROM publishing_jobs"), "no publish jobs");
        System.out.printf("  Queue + fake provider: %d items in %d ms (cooldown accelerated)%n", n, Duration.ofNanos(System.nanoTime() - start).toMillis());
    }

    private void concurrentStart() throws Exception {
        UUID w = workspace(), other = workspace();
        seed(w, 10);
        seed(other, 2);
        var q = queue(new FakeSync());
        var executor = Executors.newFixedThreadPool(8);
        try {
            var latch = new CountDownLatch(1);
            var futures = new ArrayList<Future<UUID>>();
            for (int i = 0; i < 8; i++)
                futures.add(executor.submit(() -> {
                    latch.await();
                    return q.start(w, null).id();
                }));
            latch.countDown();
            var ids = new HashSet<UUID>();
            for (var f : futures) ids.add(f.get(30, TimeUnit.SECONDS));
            eq(1, ids.size(), "one run despite 8 starts");
            eq(10L, latest(q, w).total(), "no duplicate items");
            var second = q.start(other, null);
            require(!ids.contains(second.id()), "other workspace has separate run");
            var claim = q.claim();
            require(claim != null, "first claim");
            var f1 = executor.submit(q::claim);
            var f2 = executor.submit(q::claim);
            eq(null, f1.get(30, TimeUnit.SECONDS), "second worker blocked");
            eq(null, f2.get(30, TimeUnit.SECONDS), "third worker blocked");
            eq(1L, count("SELECT count(*) FROM facebook_identity_sync_items WHERE status='RUNNING'"), "one active claim globally");
        } finally {
            executor.shutdownNow();
        }
    }

    private void failureIsolation() {
        UUID w = workspace();
        seed(w, 4);
        var fake = new FakeSync();
        fake.failures.add(identity(1));
        var q = queue(fake);
        db.update("UPDATE facebook_authorized_identities SET health_status='DISCONNECTED',user_token_encrypted=NULL WHERE workspace_id=? AND facebook_user_id=?", w, identity(2));
        db.update("UPDATE facebook_authorized_identities SET token_expires_at=now()-interval '1 minute' WHERE workspace_id=? AND facebook_user_id=?", w, identity(3));
        var run = q.start(w, null);
        q.processOne();
        q.processOne();
        var afterFailure = latest(q, w);
        eq(1L, afterFailure.failed(), "failed fake provider");
        eq(3L, afterFailure.pending(), "real 60s cooldown holds remaining items");
        ageFinished(run.id());
        for (int i = 0; i < 3; i++) {
            q.processOne();
            ageFinished(run.id());
        }
        var end = latest(q, w);
        eq("COMPLETED", end.status(), "completed");
        eq(1L, end.failed(), "one failure");
        eq(2L, end.skipped(), "disconnected and expired skipped");
        eq(1L, end.succeeded(), "other identity continues");
        eq(2, fake.calls.size(), "only eligible accounts called");
        eq(1, end.errors().size(), "one UI failure");
        require(!end.errors().getFirst().message().contains("simulated provider failure"), "raw provider error hidden");
        eq("DISCONNECTED", db.queryForObject("SELECT health_status FROM facebook_authorized_identities WHERE workspace_id=? AND facebook_user_id=?", String.class, w, identity(2)), "no reactivation of disconnect");
    }

    private void recovery() {
        UUID w = workspace();
        seed(w, 3);
        var fake = new FakeSync();
        var q = queue(fake);
        var r = q.start(w, null);
        var claim = q.claim();
        require(claim != null, "claim before interruption");
        db.update("UPDATE facebook_identity_sync_items SET lease_until=now()-interval '1 second' WHERE run_id=? AND claim_id=?", r.id(), claim.claimId());
        var restarted = queue(fake);
        restarted.processOne();
        var after = latest(restarted, w);
        eq(r.id(), after.id(), "same run after new service instance");
        eq(1L, after.failed(), "expired claim reported failed");
        eq(2L, after.pending(), "unprocessed items retained");
        ageFinished(r.id());
        for (int i = 0; i < 2; i++) {
            restarted.processOne();
            ageFinished(r.id());
        }
        var end = latest(restarted, w);
        eq("COMPLETED", end.status(), "recovered run");
        eq(2L, end.succeeded(), "pending items completed");
        require(!fake.calls.containsKey(w + ":" + claim.userId()), "interrupted item not silently retried");
    }

    private static void field(Object instance, String name, Object value) throws Exception {
        Field f = instance.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(instance, value);
    }

    private void workerLifecycle() throws Exception {
        UUID w = workspace();
        seed(w, 2);
        var fake = new FakeSync();
        var q = queue(fake);
        var r = q.start(w, null);
        var worker = new FacebookIdentitySyncWorker(q);
        field(worker, "enabled", true);
        field(worker, "delay", 2000L);
        try {
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && !"COMPLETED".equals(latest(q, w).status())) Thread.sleep(250);
            eq(2L, latest(q, w).succeeded(), "real worker completed both");
            eq("COMPLETED", latest(q, w).status(), "worker status");
        } finally {
            worker.stop();
        }
        var restored = latest(queue(fake), w);
        eq(r.id(), restored.id(), "new service retrieves result");
        eq(2L, restored.succeeded(), "persisted result");
    }

    private UUID page(UUID w) {
        UUID p = UUID.randomUUID();
        db.update("INSERT INTO social_accounts(id,workspace_id,platform,external_account_id,display_name,status) VALUES (?,?,'FACEBOOK_PAGE',?,'QA Page','CONNECTED')", p, w, "fake-" + p);
        return p;
    }

    private UUID grant(UUID page, String identity) {
        UUID g = UUID.randomUUID();
        db.update("INSERT INTO facebook_page_connections(id,social_account_id,facebook_user_id,access_token_encrypted,status,last_connected_at) VALUES (?,?,?,'FAKE-NOT-A-TOKEN','CONNECTED',now())", g, page, identity);
        return g;
    }

    private UUID job(UUID w, UUID page, UUID grant, String state) {
        UUID p = UUID.randomUUID(), j = UUID.randomUUID();
        db.update("INSERT INTO posts(id,workspace_id,content,status,approval_status) VALUES (?,?,'QA ONLY - NEVER PUBLISH','READY','APPROVED')", p, w);
        db.update("INSERT INTO publishing_jobs(id,post_id,social_account_id,facebook_connection_id,status,scheduled_at) VALUES (?,?,?,?,?,now()-interval '5 seconds')", j, p, page, grant, state);
        return j;
    }

    private String status(UUID job) {
        return db.queryForObject("SELECT status FROM publishing_jobs WHERE id=?", String.class, job);
    }

    private String health(UUID w, String id) {
        return db.queryForObject("SELECT health_status FROM facebook_authorized_identities WHERE workspace_id=? AND facebook_user_id=?", String.class, w, id);
    }

    private String grantStatus(UUID g) {
        return db.queryForObject("SELECT status FROM facebook_page_connections WHERE id=?", String.class, g);
    }

    private FanpageQueueProcessor processor() throws Exception {
        var p = new FanpageQueueProcessor(db, new ObjectMapper(), tx, null);
        field(p, "publishingMode", "MOCK");
        field(p, "bulkEnabled", false);
        return p;
    }

    // Calls ONLY actual claim methods; never processBatch(), publish(), or publishQueuedJob().
    private Object claimRegular(UUID job) throws Exception {
        var p = processor();
        field(p, "mockJobId", job.toString());
        Method m = p.getClass().getDeclaredMethod("claim");
        m.setAccessible(true);
        return m.invoke(p);
    }

    private void expiredHealth() throws Exception {
        UUID w = workspace();
        seed(w, 2);
        UUID page = page(w), a = grant(page, identity(1)), b = grant(page, identity(2));
        UUID j1 = job(w, page, a, "PENDING"), j2 = job(w, page, b, "PENDING"), old = job(w, page, null, "PENDING");
        db.update("UPDATE facebook_authorized_identities SET token_expires_at=now()-interval '1 minute' WHERE workspace_id=? AND facebook_user_id=?", w, identity(1));
        realIdentity().checkDue(5, 30);
        eq("REAUTH_REQUIRED", health(w, identity(1)), "expired identity health");
        eq("HEALTHY", health(w, identity(2)), "other identity unchanged");
        eq("IDENTITY_BLOCKED", grantStatus(a), "expired grant blocked");
        eq("CONNECTED", grantStatus(b), "other grant connected");
        require(db.queryForObject("SELECT health_message FROM facebook_authorized_identities WHERE workspace_id=? AND facebook_user_id=?", String.class, w, identity(1)).contains("Vui lòng kết nối lại"), "UI health message persisted");
        eq(null, claimRegular(j1), "blocked bound job not claimed");
        eq(null, claimRegular(old), "ambiguous legacy job blocked");
        require(claimRegular(j2) != null, "healthy grant can claim");
        eq("PROCESSING", status(j2), "healthy job claimed only");
        eq("PENDING", status(j1), "blocked job retained");
    }

    private void disconnect() throws Exception {
        UUID w = workspace(), other = workspace();
        seed(w, 2);
        seed(other, 1);
        UUID page = page(w), a = grant(page, identity(1)), b = grant(page, identity(2)), outside = grant(page(other), identity(1));
        UUID pending = job(w, page, a, "PENDING"), healthy = job(w, page, b, "PENDING"), old = job(w, page, null, "PENDING");
        UUID published = job(w, page, a, "PUBLISHED"), review = job(w, page, a, "NEEDS_REVIEW");
        var r = realIdentity().disconnect(w, identity(1));
        eq(1, r.disconnectedPages(), "affected grants");
        eq(1L, r.waitingJobs(), "bound waiting job count");
        eq(1L, r.unboundJobs(), "legacy waiting count");
        eq("DISCONNECTED", health(w, identity(1)), "identity disconnected");
        eq(null, db.queryForObject("SELECT user_token_encrypted FROM facebook_authorized_identities WHERE workspace_id=? AND facebook_user_id=?", String.class, w, identity(1)), "token cleared");
        eq("DISCONNECTED", grantStatus(a), "own grant");
        eq("CONNECTED", grantStatus(b), "other grant");
        eq("CONNECTED", grantStatus(outside), "other workspace");
        eq("PUBLISHED", status(published), "published preserved");
        eq("NEEDS_REVIEW", status(review), "uncertain preserved");
        eq(null, claimRegular(pending), "disconnected bound job not claimed");
        eq(null, claimRegular(old), "legacy not silently routed");
        require(claimRegular(healthy) != null, "healthy grant still claimable");
    }

    private void disconnectBusy() {
        UUID w = workspace();
        seed(w, 1);
        UUID p = page(w), g = grant(p, identity(1));
        job(w, p, g, "PROCESSING");
        try {
            realIdentity().disconnect(w, identity(1));
            throw new AssertionError("disconnect must reject active publishing");
        } catch (ApiException e) {
            eq(409, e.status.value(), "conflict status");
        }
        eq("HEALTHY", health(w, identity(1)), "identity unchanged");
        eq("CONNECTED", grantStatus(g), "grant unchanged");
    }

    private void bulkGuard() throws Exception {
        UUID w = workspace();
        seed(w, 2);
        UUID p = page(w), a = grant(p, identity(1)), b = grant(p, identity(2));
        UUID blocked = job(w, p, a, "PENDING"), healthy = job(w, p, b, "PENDING");
        UUID run = UUID.randomUUID();
        db.update("INSERT INTO facebook_bulk_runs(id,workspace_id,status) VALUES (?,?,'RUNNING')", run, w);
        db.update("INSERT INTO facebook_bulk_run_jobs(bulk_run_id,publishing_job_id) VALUES (?,?),(?,?)", run, blocked, run, healthy);
        realIdentity().disconnect(w, identity(1));
        var processor = processor();
        field(processor, "bulkEnabled", true);
        field(processor, "mockBulkRunId", run.toString());
        field(processor, "maxImagesPerPost", 10);
        Method m = processor.getClass().getDeclaredMethod("claimBulk");
        m.setAccessible(true);
        require(m.invoke(processor) != null, "healthy bulk job claimed");
        eq("PROCESSING", status(healthy), "healthy bulk job");
        eq("PENDING", status(blocked), "blocked bulk job remains pending");
        eq(null, m.invoke(processor), "cannot claim remaining blocked bulk job");
    }

    private UUID run(UUID w, String state, UUID... jobs) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO facebook_bulk_runs(id,workspace_id,status) VALUES (?,?,?)", id, w, state);
        for (UUID j : jobs)
            db.update("INSERT INTO facebook_bulk_run_jobs(bulk_run_id,publishing_job_id) VALUES (?,?)", id, j);
        return id;
    }

    private void media(UUID job, String type, String url) {
        db.update("INSERT INTO post_media(post_id,source_url,public_url,media_type) SELECT post_id,?,?,? FROM publishing_jobs WHERE id=?", url == null ? "qa source" : url, url, type, job);
    }

    private FanpageQueueProcessor pilot(UUID run, boolean av) throws Exception {
        var p = processor();
        field(p, "publishingMode", "LIVE");
        field(p, "bulkEnabled", true);
        field(p, "pilotRunId", run == null ? "" : run.toString());
        field(p, "bulkAvEnabled", av);
        field(p, "videoPublishingEnabled", true);
        field(p, "reelsPublishingEnabled", true);
        field(p, "videoAutoVerify", true);
        field(p, "reelAutoVerify", true);
        field(p, "maxImagesPerPost", 10);
        return p;
    }

    private Object claimPilot(FanpageQueueProcessor p) throws Exception {
        Method m = p.getClass().getDeclaredMethod("claimBulk");
        m.setAccessible(true);
        return m.invoke(p);
    }

    private void pilotClaim() throws Exception {
        UUID w = workspace();
        seed(w, 1);
        UUID p = page(w), g = grant(p, identity(1));
        UUID text = job(w, p, g, "PENDING"), image = job(w, p, g, "PENDING"), video = job(w, p, g, "PENDING"), reel = job(w, p, g, "PENDING"), mixed = job(w, p, g, "PENDING"), missing = job(w, p, g, "PENDING"), overdue = job(w, p, g, "PENDING"), unbound = job(w, p, null, "PENDING"), other = job(w, p, g, "PENDING");
        media(image, "IMAGE", "https://res.cloudinary.com/qa/image/a.png");
        media(video, "VIDEO", "https://res.cloudinary.com/qa/video/a.mp4");
        media(reel, "REEL", "https://res.cloudinary.com/qa/video/b.mp4");
        media(mixed, "VIDEO", "https://res.cloudinary.com/qa/video/a.mp4");
        media(mixed, "IMAGE", "https://res.cloudinary.com/qa/image/a.png");
        media(missing, "REEL", null);
        db.update("UPDATE publishing_jobs SET scheduled_at=now()-interval '20 minutes' WHERE id=?", overdue);
        UUID target = run(w, "RUNNING", text, image, video, reel, mixed, missing, overdue, unbound);
        run(w, "RUNNING", other);
        var worker = pilot(target, true);
        for (int i = 0; i < 4; i++) require(claimPilot(worker) != null, "expected claim " + i);
        eq(null, claimPilot(worker), "no additional claims");
        for (UUID id : List.of(text, image, video, reel)) eq("PROCESSING", status(id), "accepted media");
        for (UUID id : List.of(mixed, missing, overdue, unbound, other))
            eq("PENDING", status(id), "blocked or out of scope");
    }

    private void pilotDisabled() throws Exception {
        UUID w = workspace();
        seed(w, 1);
        UUID p = page(w), g = grant(p, identity(1));
        UUID v = job(w, p, g, "PENDING"), r = job(w, p, g, "PENDING"), t = job(w, p, g, "PENDING");
        media(v, "VIDEO", "https://res.cloudinary.com/qa/video/a.mp4");
        media(r, "REEL", "https://res.cloudinary.com/qa/video/b.mp4");
        UUID target = run(w, "RUNNING", v, r, t);
        var worker = pilot(target, false);
        require(claimPilot(worker) != null, "text still claimable");
        eq("PROCESSING", status(t), "text claimed");
        eq(null, claimPilot(worker), "no AV while disabled");
        var noScope = pilot(null, true);
        try {
            claimPilot(noScope);
            throw new AssertionError("scope required");
        } catch (java.lang.reflect.InvocationTargetException ex) {
            require(ex.getCause() instanceof IllegalStateException, "missing scope rejected");
        }
        var av = pilot(target, true);
        field(av, "reelAutoVerify", false);
        require(claimPilot(av) != null, "video claimable");
        eq("PROCESSING", status(v), "video claimed");
        eq(null, claimPilot(av), "reel blocked without verifier");
        eq("PENDING", status(r), "reel stays pending");
    }

    private void pilotStart() throws Exception {
        UUID w = workspace();
        seed(w, 1);
        UUID p = page(w), g = grant(p, identity(1)), v = job(w, p, g, "PENDING"), r = job(w, p, g, "PENDING"), other = job(w, p, g, "PENDING");
        media(v, "VIDEO", "https://res.cloudinary.com/qa/video/a.mp4");
        media(r, "REEL", "https://res.cloudinary.com/qa/video/b.mp4");
        UUID target = run(w, "DRAFT", v, r), foreign = run(w, "DRAFT", other);
        db.update("UPDATE publishing_jobs SET scheduled_at=now()+CASE WHEN id=? THEN interval '10 minutes' ELSE interval '15 minutes' END WHERE id IN (?,?)", v, v, r);
        var service = new FacebookBulkRunService(db, new WorkspaceBulkPermissionService(null) {
            @Override
            public void requireManager(UUID workspace, UUID user) {
                eq(w, workspace, "workspace scoped");
            }
        });
        for (String f : List.of("liveStartEnabled", "legacyPublisherEnabled", "schedulerBulkEnabled", "workerBulkEnabled", "bulkAvEnabled", "videoEnabled", "reelEnabled", "videoAutoVerify", "reelAutoVerify"))
            field(service, f, true);
        field(service, "schedulerMode", "LIVE");
        field(service, "workerMode", "LIVE");
        field(service, "graphBaseUrl", "https://graph.facebook.com");
        field(service, "pilotRunId", target.toString());
        field(service, "maxImagesPerPost", 10);
        UUID user = UUID.randomUUID();
        eq(false, service.liveReadiness(user, foreign).enabled(), "other run blocked");
        eq(true, service.liveReadiness(user, target).enabled(), "pilot configured");
        var jobs = service.jobs(user, target);
        eq(2, jobs.size(), "persisted run jobs");
        require(jobs.stream().allMatch(FacebookBulkRunService.RunJob::mediaEligible), "AV media accepted");
        var newStart = OffsetDateTime.now().plusHours(1);
        var moved = tx.execute(s -> service.reschedule(user, target, newStart));
        eq(2, moved.updatedJobs(), "two existing jobs moved");
        eq(5L, Duration.between(moved.firstScheduledAt(), moved.lastScheduledAt()).toMinutes(), "spacing preserved");
        field(service, "reelAutoVerify", false);
        try {
            tx.execute(s -> service.start(user, target));
            throw new AssertionError("start should reject disabled reel");
        } catch (org.springframework.web.server.ResponseStatusException ex) {
            eq(409, ex.getStatusCode().value(), "disabled media conflict");
        }
        field(service, "reelAutoVerify", true);
        eq("RUNNING", tx.execute(s -> service.start(user, target)).status(), "selected run starts");
        eq("DRAFT", service.get(user, foreign).status(), "other draft unchanged");
        eq("PENDING", status(v), "start does not publish");
    }
}
