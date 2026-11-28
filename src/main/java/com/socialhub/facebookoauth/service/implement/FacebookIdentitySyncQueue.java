package com.socialhub.facebookoauth.service.implement;

import com.socialhub.common.ApiException;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class FacebookIdentitySyncQueue {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FacebookIdentityService identities;

    public FacebookIdentitySyncQueue(JdbcTemplate jdbc, TransactionTemplate tx, FacebookIdentityService identities) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.identities = identities;
    }

    public record Failure(String displayName, String message) {
    }

    public record Run(UUID id, String status, OffsetDateTime createdAt, OffsetDateTime completedAt,
                      long total, long pending, long running, long succeeded, long skipped, long failed,
                      long pages, long missing, long disconnected, List<Failure> errors) {
    }

    public record Latest(@JsonInclude(JsonInclude.Include.ALWAYS) Run run) {
    }

    public record Claim(UUID runId, UUID workspaceId, String userId, UUID claimId) {
    }

    // A short, transaction-scoped lock serializes queue metadata only, never HTTP calls.
    private void lock() {
        jdbc.query("SELECT pg_advisory_xact_lock(642817, 41)", rs -> {
        });
    }

    public Run start(UUID workspaceId, String userId) {
        return tx.execute(t -> {
            lock();
            Run active = find(workspaceId, true);
            if (active != null) return active;
            if (userId != null && (userId.isBlank() || userId.length() > 128))
                throw new ApiException(HttpStatus.BAD_REQUEST, "Tài khoản Facebook không hợp lệ.");
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO facebook_identity_sync_runs(id, workspace_id, status) VALUES (?, ?, 'ACTIVE')", id, workspaceId);
            int count = jdbc.update("""
                            INSERT INTO facebook_identity_sync_items(run_id, facebook_user_id, display_name)
                            SELECT ?, facebook_user_id, display_name FROM facebook_authorized_identities
                            WHERE workspace_id = ?
                            """ + (userId == null ? "" : " AND facebook_user_id = ?"),
                    userId == null ? new Object[]{id, workspaceId} : new Object[]{id, workspaceId, userId});
            if (count == 0) throw new ApiException(HttpStatus.CONFLICT, "Không có tài khoản Facebook để đồng bộ.");
            return read(workspaceId, id);
        });
    }

    public Latest latest(UUID workspaceId) {
        return new Latest(find(workspaceId, false));
    }

    private Run find(UUID workspaceId, boolean activeOnly) {
        List<UUID> ids = jdbc.query("SELECT id FROM facebook_identity_sync_runs WHERE workspace_id = ?"
                        + (activeOnly ? " AND status = 'ACTIVE'" : "") + " ORDER BY created_at DESC, id DESC LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), workspaceId);
        return ids.isEmpty() ? null : read(workspaceId, ids.getFirst());
    }

    private Run read(UUID workspaceId, UUID id) {
        List<Failure> errors = jdbc.query("""
                SELECT COALESCE(NULLIF(i.display_name,''),'Tài khoản Facebook') AS name, i.message
                FROM facebook_identity_sync_items i JOIN facebook_identity_sync_runs r ON r.id=i.run_id
                WHERE r.workspace_id=? AND r.id=? AND i.status='FAILED'
                ORDER BY i.finished_at DESC, i.facebook_user_id LIMIT 20
                """, (rs, n) -> new Failure(rs.getString(1), rs.getString(2)), workspaceId, id);
        return jdbc.queryForObject("""
                SELECT r.id, r.status, r.created_at, r.completed_at, COUNT(i.run_id) AS total,
                    COUNT(*) FILTER (WHERE i.status='PENDING') AS pending,
                    COUNT(*) FILTER (WHERE i.status='RUNNING') AS running,
                    COUNT(*) FILTER (WHERE i.status='SUCCEEDED') AS succeeded,
                    COUNT(*) FILTER (WHERE i.status='SKIPPED') AS skipped,
                    COUNT(*) FILTER (WHERE i.status='FAILED') AS failed,
                    COALESCE(SUM(i.saved_pages),0) AS pages, COALESCE(SUM(i.missing_pages),0) AS missing,
                    COALESCE(SUM(i.disconnected_pages),0) AS disconnected
                FROM facebook_identity_sync_runs r LEFT JOIN facebook_identity_sync_items i ON i.run_id=r.id
                WHERE r.workspace_id=? AND r.id=? GROUP BY r.id
                """, (rs, n) -> new Run(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class),
                rs.getLong("total"), rs.getLong("pending"), rs.getLong("running"), rs.getLong("succeeded"),
                rs.getLong("skipped"), rs.getLong("failed"), rs.getLong("pages"), rs.getLong("missing"), rs.getLong("disconnected"), errors), workspaceId, id);
    }

    private void closeFinished() {
        jdbc.update("""
                UPDATE facebook_identity_sync_runs r SET status='COMPLETED', completed_at=CURRENT_TIMESTAMP
                WHERE status='ACTIVE' AND NOT EXISTS (SELECT 1 FROM facebook_identity_sync_items i
                    WHERE i.run_id=r.id AND i.status IN ('PENDING','RUNNING'))
                """);
    }

    public Claim claim() {
        return tx.execute(t -> {
            lock();
            // Interrupted work is reported, not silently repeated. Other pending items survive restart.
            jdbc.update("""
                    UPDATE facebook_identity_sync_items SET status='FAILED', finished_at=CURRENT_TIMESTAMP,
                        message='Tiến trình đồng bộ bị gián đoạn. Có thể đồng bộ lại tài khoản này.', lease_until=NULL
                    WHERE status='RUNNING' AND lease_until <= CURRENT_TIMESTAMP
                    """);
            closeFinished();
            Boolean blocked = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM facebook_identity_sync_items WHERE status='RUNNING')
                        OR EXISTS (SELECT 1 FROM facebook_identity_sync_items WHERE finished_at > CURRENT_TIMESTAMP - INTERVAL '2 seconds')
                        OR EXISTS (SELECT 1 FROM facebook_identity_sync_items WHERE status='FAILED'
                            AND finished_at > CURRENT_TIMESTAMP - INTERVAL '60 seconds')
                    """, Boolean.class);
            if (Boolean.TRUE.equals(blocked)) return null;
            // One provider-facing item at a time across cooperating worker instances.
            UUID claimId = UUID.randomUUID();
            List<Claim> claims = jdbc.query("""
                    WITH candidate AS (
                        SELECT i.run_id, i.facebook_user_id FROM facebook_identity_sync_items i
                        JOIN facebook_identity_sync_runs r ON r.id=i.run_id
                        WHERE r.status='ACTIVE' AND i.status='PENDING'
                        ORDER BY r.created_at, r.id, i.facebook_user_id LIMIT 1 FOR UPDATE OF i SKIP LOCKED
                    )
                    UPDATE facebook_identity_sync_items i SET status='RUNNING', claim_id=?,
                        lease_until=CURRENT_TIMESTAMP + INTERVAL '10 minutes'
                    FROM candidate c, facebook_identity_sync_runs r
                    WHERE i.run_id=c.run_id AND i.facebook_user_id=c.facebook_user_id AND r.id=i.run_id
                    RETURNING i.run_id, r.workspace_id, i.facebook_user_id, i.claim_id
                    """, (rs, n) -> new Claim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class)), claimId);
            return claims.isEmpty() ? null : claims.getFirst();
        });
    }

    public void processOne() {
        Claim c = claim();
        if (c == null) return;
        Boolean eligible = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM facebook_authorized_identities WHERE workspace_id=? AND facebook_user_id=?
                    AND user_token_encrypted IS NOT NULL AND health_status NOT IN ('DISCONNECTED','REAUTH_REQUIRED')
                    AND (token_expires_at IS NULL OR token_expires_at > CURRENT_TIMESTAMP))
                """, Boolean.class, c.workspaceId(), c.userId());
        if (!Boolean.TRUE.equals(eligible)) {
            finish(c, "SKIPPED", null, "Tài khoản chưa có quyền đồng bộ hợp lệ.");
            return;
        }
        FacebookIdentityService.SyncResult result;
        try {
            result = identities.sync(c.workspaceId(), c.userId());
        } catch (RuntimeException ex) {
            // No provider payload, token, or stack trace returned to the UI.
            finish(c, "FAILED", null, "Không đồng bộ được. Kiểm tra quyền và kết nối Facebook, sau đó thử lại.");
            return;
        }
        finish(c, "SUCCEEDED", result, null);
    }

    private void finish(Claim c, String state, FacebookIdentityService.SyncResult result, String message) {
        tx.executeWithoutResult(t -> {
            lock();
            jdbc.update("""
                            UPDATE facebook_identity_sync_items SET status=?, finished_at=CURRENT_TIMESTAMP, lease_until=NULL,
                                saved_pages=?, missing_pages=?, disconnected_pages=?, message=?
                            WHERE run_id=? AND facebook_user_id=? AND claim_id=? AND status='RUNNING'
                            """, state, result == null ? 0 : result.savedPages(), result == null ? 0 : result.notReturned(),
                    result == null ? 0 : result.skippedDisconnected(), message, c.runId(), c.userId(), c.claimId());
            closeFinished();
        });
    }
}
