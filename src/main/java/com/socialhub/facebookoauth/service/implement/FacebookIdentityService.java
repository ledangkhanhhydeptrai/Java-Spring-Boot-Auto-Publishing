package com.socialhub.facebookoauth.service.implement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.common.ApiException;
import com.socialhub.facebookoauth.storage.FacebookTokenStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class FacebookIdentityService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FacebookTokenStorage storage;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    @Value("${app.facebook.graph-version:v23.0}")
    private String graphVersion;

    public FacebookIdentityService(JdbcTemplate jdbc, TransactionTemplate tx,
                                   FacebookTokenStorage storage, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.storage = storage;
        this.json = json;
    }

    private record Grant(String id, String name, String token) {
    }

    private record Snapshot(String id, String name, List<Grant> grants) {
    }

    private record Stored(String encrypted, OffsetDateTime expiresAt, String health, OffsetDateTime checkedAt) {
    }

    private record Target(UUID workspaceId, String userId) {
    }

    public record DisconnectResult(int disconnectedPages, long waitingJobs, long unboundJobs) {
    }

    private static final class MetaTokenRejected extends Exception {
        private MetaTokenRejected() {
            super("Facebook token rejected");
        }
    }

    public record SyncResult(int availablePages, int savedPages, int skippedDisconnected, int notReturned) {
    }

    public void authorize(UUID workspaceId, String token, OffsetDateTime expiresAt) throws Exception {
        Snapshot snapshot = fetch(token);
        String encrypted = storage.encryptUserToken(token);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO facebook_authorized_identities
                        (workspace_id, facebook_user_id, display_name, user_token_encrypted,
                         token_expires_at, last_authorized_at, last_synced_at, health_status, last_checked_at, health_message)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'HEALTHY', CURRENT_TIMESTAMP, NULL)
                    ON CONFLICT (workspace_id, facebook_user_id) DO UPDATE SET
                        display_name = EXCLUDED.display_name,
                        user_token_encrypted = EXCLUDED.user_token_encrypted,
                        token_expires_at = EXCLUDED.token_expires_at,
                        last_authorized_at = EXCLUDED.last_authorized_at,
                        last_synced_at = EXCLUDED.last_synced_at, health_status = 'HEALTHY',
                        last_checked_at = CURRENT_TIMESTAMP, health_message = NULL
                    """, workspaceId, snapshot.id(), snapshot.name(), encrypted, expiresAt);
            persistPages(workspaceId, snapshot, true);
        });
    }

    public SyncResult sync(UUID workspaceId, String facebookUserId) {
        Stored stored = load(workspaceId, facebookUserId, false);
        if (stored.encrypted() != null && stored.expiresAt() != null && !stored.expiresAt().isAfter(OffsetDateTime.now())) {
            recordCheckFailure(workspaceId, facebookUserId, stored, new MetaTokenRejected());
        }
        if ("DISCONNECTED".equals(stored.health()) || "REAUTH_REQUIRED".equals(stored.health()) || stored.encrypted() == null || (stored.expiresAt() != null && !stored.expiresAt().isAfter(OffsetDateTime.now()))) {
            throw new ApiException(HttpStatus.CONFLICT, "Tài khoản cần cấp quyền lại trước khi đồng bộ.");
        }
        final Snapshot snapshot;
        try {
            snapshot = fetch(storage.decryptUserToken(stored.encrypted()), System.nanoTime() + Duration.ofMinutes(3).toNanos());
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            recordCheckFailure(workspaceId, facebookUserId, stored, ex);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Không đồng bộ được với Facebook. Hãy kiểm tra kết nối hoặc cấp quyền lại nếu cần.");
        }
        if (!facebookUserId.equals(snapshot.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "Danh tính Facebook không khớp. Không thay đổi dữ liệu.");
        }
        return tx.execute(status -> {
            Stored current = load(workspaceId, facebookUserId, true);
            if (!sameGrant(current, stored)) {
                throw new ApiException(HttpStatus.CONFLICT, "Kết nối vừa được cập nhật. Vui lòng đồng bộ lại.");
            }
            SyncResult result = persistPages(workspaceId, snapshot, false);
            jdbc.update("""
                    UPDATE facebook_authorized_identities SET display_name = ?, last_synced_at = CURRENT_TIMESTAMP,
                        health_status = 'HEALTHY', last_checked_at = CURRENT_TIMESTAMP, health_message = NULL
                    WHERE workspace_id = ? AND facebook_user_id = ?
                    """, snapshot.name(), workspaceId, facebookUserId);
            return result;
        });
    }

    private Stored load(UUID workspaceId, String id, boolean lock) {
        List<Stored> values = jdbc.query("""
                SELECT user_token_encrypted, token_expires_at, health_status, last_checked_at FROM facebook_authorized_identities
                WHERE workspace_id = ? AND facebook_user_id = ?
                """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Stored(rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getObject(4, OffsetDateTime.class)), workspaceId, id);
        if (values.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản Facebook.");
        return values.getFirst();
    }

    private SyncResult persistPages(UUID workspaceId, Snapshot snapshot, boolean explicitAuthorization) {
        Map<String, String> existing = new HashMap<>();
        jdbc.query("""
                SELECT a.external_account_id, c.status FROM facebook_page_connections c
                JOIN social_accounts a ON a.id = c.social_account_id
                WHERE a.workspace_id = ? AND a.platform = 'FACEBOOK_PAGE' AND c.facebook_user_id = ? FOR UPDATE OF c
                """, rs -> {
            existing.put(rs.getString(1), rs.getString(2));
        }, workspaceId, snapshot.id());
        int saved = 0, skipped = 0;
        Set<String> returned = new HashSet<>();
        for (Grant grant : snapshot.grants()) {
            returned.add(grant.id());
            // An ordinary sync must not undo an intentional local disconnect.
            if (!explicitAuthorization && "DISCONNECTED".equals(existing.get(grant.id()))) {
                skipped++;
                continue;
            }
            try {
                storage.save(workspaceId, snapshot.id(), grant.id(), grant.name(), grant.token());
                saved++;
            } catch (Exception ex) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không lưu được kết nối Fanpage. Chưa áp dụng thay đổi của lần đồng bộ này.");
            }
        }
        int missing = (int) existing.keySet().stream().filter(id -> !returned.contains(id)).count();
        // The snapshot is complete, and includes only Pages with CREATE_CONTENT.
        // Do not change manually disconnected grants or rewrite Job outcomes.
        blockMissingPages(workspaceId, snapshot);
        return new SyncResult(returned.size(), saved, skipped, missing);
    }

    private static boolean sameGrant(Stored current, Stored expected) {
        return current.encrypted() != null && Objects.equals(current.encrypted(), expected.encrypted())
                && Objects.equals(current.checkedAt(), expected.checkedAt())
                && !"DISCONNECTED".equals(current.health()) && !"REAUTH_REQUIRED".equals(current.health());
    }

    // A local disconnect is deliberately not a provider-wide revocation.
    public DisconnectResult disconnect(UUID workspaceId, String facebookUserId) {
        return tx.execute(status -> {
            load(workspaceId, facebookUserId, true);
            long processing = jobCount(workspaceId, facebookUserId, true, false);
            if (processing > 0) throw new ApiException(HttpStatus.CONFLICT,
                    "Có lịch đăng đang xử lý trên Fanpage liên quan. Vui lòng chờ xử lý xong trước khi ngắt kết nối.");
            int affected = jdbc.update("""
                    UPDATE facebook_page_connections c SET status = 'DISCONNECTED'
                    FROM social_accounts a WHERE a.id = c.social_account_id
                      AND a.workspace_id = ? AND a.platform = 'FACEBOOK_PAGE' AND c.facebook_user_id = ?
                      AND c.status <> 'DISCONNECTED'
                    """, workspaceId, facebookUserId);
            jdbc.update("""
                    UPDATE facebook_authorized_identities SET health_status = 'DISCONNECTED',
                        user_token_encrypted = NULL, token_expires_at = NULL,
                        last_checked_at = CURRENT_TIMESTAMP, health_message = 'Đã ngắt kết nối trong hệ thống.'
                    WHERE workspace_id = ? AND facebook_user_id = ?
                    """, workspaceId, facebookUserId);
            return new DisconnectResult(affected, jobCount(workspaceId, facebookUserId, false, false),
                    jobCount(workspaceId, facebookUserId, false, true));
        });
    }

    private long jobCount(UUID workspaceId, String userId, boolean processing, boolean onlyUnbound) {
        String binding = onlyUnbound ? "j.facebook_connection_id IS NULL" : processing
                ? "(j.facebook_connection_id = c.id OR j.facebook_connection_id IS NULL)"
                : "j.facebook_connection_id = c.id";
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(DISTINCT j.id) FROM publishing_jobs j
                        JOIN social_accounts a ON a.id = j.social_account_id
                        JOIN facebook_page_connections c ON c.social_account_id = a.id
                        WHERE a.workspace_id = ? AND a.platform = 'FACEBOOK_PAGE' AND c.facebook_user_id = ? AND
                        """ + binding + (processing ? " AND j.status = 'PROCESSING'" : " AND j.status IN ('PENDING','RETRY','FAILED')"),
                Long.class, workspaceId, userId);
        return count == null ? 0 : count;
    }

    private void blockMissingPages(UUID workspaceId, Snapshot snapshot) {
        Set<String> available = new HashSet<>();
        for (Grant grant : snapshot.grants()) available.add(grant.id());
        List<String> connected = jdbc.queryForList("""
                SELECT a.external_account_id FROM facebook_page_connections c JOIN social_accounts a ON a.id = c.social_account_id
                WHERE a.workspace_id = ? AND a.platform = 'FACEBOOK_PAGE' AND c.facebook_user_id = ? AND c.status = 'CONNECTED'
                """, String.class, workspaceId, snapshot.id());
        for (String pageId : connected)
            if (!available.contains(pageId)) {
                jdbc.update("""
                        UPDATE facebook_page_connections c SET status = 'PERMISSION_BLOCKED'
                        FROM social_accounts a WHERE a.id = c.social_account_id AND a.workspace_id = ?
                        AND a.platform = 'FACEBOOK_PAGE' AND c.facebook_user_id = ? AND a.external_account_id = ?
                        AND c.status = 'CONNECTED'
                        """, workspaceId, snapshot.id(), pageId);
            }
    }

    private void recordCheckFailure(UUID workspaceId, String userId, Stored expected, Exception error) {
        tx.executeWithoutResult(status -> {
            if (!sameGrant(load(workspaceId, userId, true), expected)) return;
            boolean definitive = error instanceof MetaTokenRejected;
            updateHealth(workspaceId, userId, definitive ? "REAUTH_REQUIRED" : "CHECK_ERROR",
                    definitive ? "Facebook không còn chấp nhận quyền đã lưu. Vui lòng kết nối lại."
                            : "Chưa kiểm tra được Facebook. Có thể do kết nối hoặc giới hạn API; chưa kết luận tài khoản mất quyền.");
            if (definitive) blockIdentityPages(workspaceId, userId);
        });
    }

    private void blockIdentityPages(UUID workspaceId, String userId) {
        jdbc.update("""
                UPDATE facebook_page_connections c SET status = 'IDENTITY_BLOCKED'
                FROM social_accounts a WHERE a.id = c.social_account_id AND a.workspace_id = ?
                AND a.platform = 'FACEBOOK_PAGE' AND c.facebook_user_id = ? AND c.status = 'CONNECTED'
                """, workspaceId, userId);
    }

    private void updateHealth(UUID workspaceId, String userId, String health, String message) {
        jdbc.update("""
                UPDATE facebook_authorized_identities SET health_status = ?, health_message = ?, last_checked_at = CURRENT_TIMESTAMP
                WHERE workspace_id = ? AND facebook_user_id = ?
                """, health, message, workspaceId, userId);
    }

    public void checkDue(int limit, int intervalMinutes) {
        List<Target> targets = jdbc.query("""
                SELECT workspace_id, facebook_user_id FROM facebook_authorized_identities
                WHERE user_token_encrypted IS NOT NULL AND health_status NOT IN ('DISCONNECTED','REAUTH_REQUIRED')
                  AND NOT EXISTS (SELECT 1 FROM facebook_identity_sync_items si JOIN facebook_identity_sync_runs sr ON sr.id=si.run_id
                      WHERE sr.workspace_id=facebook_authorized_identities.workspace_id AND si.facebook_user_id=facebook_authorized_identities.facebook_user_id
                        AND sr.status='ACTIVE' AND si.status = 'RUNNING')
                  AND (last_checked_at IS NULL OR last_checked_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 minute')
                       OR (token_expires_at <= CURRENT_TIMESTAMP))
                ORDER BY last_checked_at NULLS FIRST, workspace_id, facebook_user_id LIMIT ?
                """, (rs, n) -> new Target(rs.getObject(1, UUID.class), rs.getString(2)), intervalMinutes, limit);
        for (Target target : targets) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                check(target.workspaceId(), target.userId());
            } catch (RuntimeException ex) {
                // Avoid exposing token / provider response. A broken account cannot stop other checks.
                org.slf4j.LoggerFactory.getLogger(FacebookIdentityService.class).warn("Facebook identity health check could not complete for workspace {}", target.workspaceId());
            }
        }
    }

    private void check(UUID workspaceId, String userId) {
        Stored stored = load(workspaceId, userId, false);
        if (!sameGrant(stored, stored)) return;
        if (stored.expiresAt() != null && !stored.expiresAt().isAfter(OffsetDateTime.now())) {
            recordCheckFailure(workspaceId, userId, stored, new MetaTokenRejected());
            return;
        }
        final Snapshot snapshot;
        try {
            snapshot = fetch(storage.decryptUserToken(stored.encrypted()));
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            recordCheckFailure(workspaceId, userId, stored, ex);
            return;
        }
        if (!userId.equals(snapshot.id())) {
            recordCheckFailure(workspaceId, userId, stored, new MetaTokenRejected());
            return;
        }
        tx.executeWithoutResult(status -> {
            if (!sameGrant(load(workspaceId, userId, true), stored)) return;
            blockMissingPages(workspaceId, snapshot);
            updateHealth(workspaceId, userId, "HEALTHY", null);
        });
    }

    private Snapshot fetch(String token) throws Exception {
        return fetch(token, 0L);
    }

    private Snapshot fetch(String token, long deadline) throws Exception {
        JsonNode me = get("/me", "id,name", null, token);
        String id = me.path("id").asText("");
        String name = me.path("name").asText("");
        if (id.isBlank() || id.length() > 128) throw new IllegalStateException("Missing Facebook identity");
        Map<String, Grant> grants = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        String after = null;
        // A safety bound rejects an incomplete snapshot; it never silently truncates the list.
        for (int page = 0; page < 1000; page++) {
            if (deadline != 0L && System.nanoTime() - deadline >= 0)
                throw new IllegalStateException("Page sync time budget exceeded");
            JsonNode response = get("/me/accounts", "id,name,access_token,tasks", after, token);
            JsonNode data = response.path("data");
            if (!data.isArray()) throw new IllegalStateException("Incomplete Page listing");
            for (JsonNode item : data) {
                String pageId = item.path("id").asText("");
                String pageName = item.path("name").asText("");
                String pageToken = item.path("access_token").asText("");
                JsonNode tasks = item.path("tasks");
                if (pageId.isBlank() || !tasks.isArray())
                    throw new IllegalStateException("Incomplete Page permission snapshot");
                boolean canPublish = false;
                for (JsonNode task : tasks) if ("CREATE_CONTENT".equals(task.asText())) canPublish = true;
                if (canPublish && pageToken.isBlank())
                    throw new IllegalStateException("Incomplete Page token snapshot");
                if (canPublish)
                    grants.put(pageId, new Grant(pageId, pageName.isBlank() ? pageId : pageName, pageToken));
            }
            JsonNode paging = response.path("paging");
            if (paging.path("next").asText("").isBlank()) return new Snapshot(id, name, List.copyOf(grants.values()));
            // Never follow a provider-supplied URL containing tokens or an arbitrary hostname.
            after = paging.path("cursors").path("after").asText("");
            if (after.isBlank() || !cursors.add(after)) throw new IllegalStateException("Incomplete Page pagination");
        }
        throw new IllegalStateException("Page pagination limit reached");
    }

    private JsonNode get(String path, String fields, String after, String token) throws Exception {
        var builder = UriComponentsBuilder.fromUriString("https://graph.facebook.com/" + graphVersion + path)
                .queryParam("fields", fields);
        if (path.equals("/me/accounts")) builder.queryParam("limit", 100);
        if (after != null) builder.queryParam("after", "{cursor}");
        URI uri = after == null ? builder.build().encode().toUri()
                : builder.encode().buildAndExpand(after).toUri();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = json.readTree(response.body());
        if (body != null && body.path("error").path("code").asInt(-1) == 190)
            throw new MetaTokenRejected();
        if (response.statusCode() / 100 != 2 || body == null || body.has("error"))
            throw new IllegalStateException("Meta request failed");
        return body;
    }
}
