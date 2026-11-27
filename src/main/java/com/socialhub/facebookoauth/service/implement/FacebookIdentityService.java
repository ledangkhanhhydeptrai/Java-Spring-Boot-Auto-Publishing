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
    @Value("${app.facebook.graph-version:v23.0}") private String graphVersion;

    public FacebookIdentityService(JdbcTemplate jdbc, TransactionTemplate tx,
                                   FacebookTokenStorage storage, ObjectMapper json) {
        this.jdbc = jdbc; this.tx = tx; this.storage = storage; this.json = json;
    }

    private record Grant(String id, String name, String token) {}
    private record Snapshot(String id, String name, List<Grant> grants) {}
    private record Stored(String encrypted, OffsetDateTime expiresAt) {}
    public record SyncResult(int availablePages, int savedPages, int skippedDisconnected, int notReturned) {}

    public void authorize(UUID workspaceId, String token, OffsetDateTime expiresAt) throws Exception {
        Snapshot snapshot = fetch(token);
        String encrypted = storage.encryptUserToken(token);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                INSERT INTO facebook_authorized_identities
                    (workspace_id, facebook_user_id, display_name, user_token_encrypted,
                     token_expires_at, last_authorized_at, last_synced_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (workspace_id, facebook_user_id) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    user_token_encrypted = EXCLUDED.user_token_encrypted,
                    token_expires_at = EXCLUDED.token_expires_at,
                    last_authorized_at = EXCLUDED.last_authorized_at,
                    last_synced_at = EXCLUDED.last_synced_at
                """, workspaceId, snapshot.id(), snapshot.name(), encrypted, expiresAt);
            persistPages(workspaceId, snapshot, true);
        });
    }

    public SyncResult sync(UUID workspaceId, String facebookUserId) {
        Stored stored = load(workspaceId, facebookUserId, false);
        if (stored.encrypted() == null || (stored.expiresAt() != null && !stored.expiresAt().isAfter(OffsetDateTime.now()))) {
            throw new ApiException(HttpStatus.CONFLICT, "Tài khoản cần cấp quyền lại trước khi đồng bộ.");
        }
        final Snapshot snapshot;
        try {
            snapshot = fetch(storage.decryptUserToken(stored.encrypted()));
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Không đồng bộ được với Facebook. Hãy kiểm tra kết nối hoặc cấp quyền lại nếu cần.");
        }
        if (!facebookUserId.equals(snapshot.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "Danh tính Facebook không khớp. Không thay đổi dữ liệu.");
        }
        return tx.execute(status -> {
            Stored current = load(workspaceId, facebookUserId, true);
            if (!Objects.equals(current.encrypted(), stored.encrypted())) {
                throw new ApiException(HttpStatus.CONFLICT, "Kết nối vừa được cập nhật. Vui lòng đồng bộ lại.");
            }
            SyncResult result = persistPages(workspaceId, snapshot, false);
            jdbc.update("""
                UPDATE facebook_authorized_identities SET display_name = ?, last_synced_at = CURRENT_TIMESTAMP
                WHERE workspace_id = ? AND facebook_user_id = ?
                """, snapshot.name(), workspaceId, facebookUserId);
            return result;
        });
    }

    private Stored load(UUID workspaceId, String id, boolean lock) {
        List<Stored> values = jdbc.query("""
            SELECT user_token_encrypted, token_expires_at FROM facebook_authorized_identities
            WHERE workspace_id = ? AND facebook_user_id = ?
            """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Stored(rs.getString(1), rs.getObject(2, OffsetDateTime.class)), workspaceId, id);
        if (values.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản Facebook.");
        return values.getFirst();
    }

    private SyncResult persistPages(UUID workspaceId, Snapshot snapshot, boolean explicitAuthorization) {
        Map<String, String> existing = new HashMap<>();
        jdbc.query("""
            SELECT a.external_account_id, c.status FROM facebook_page_connections c
            JOIN social_accounts a ON a.id = c.social_account_id
            WHERE a.workspace_id = ? AND a.platform = 'FACEBOOK_PAGE' AND c.facebook_user_id = ? FOR UPDATE OF c
            """, rs -> { existing.put(rs.getString(1), rs.getString(2)); }, workspaceId, snapshot.id());
        int saved = 0, skipped = 0;
        Set<String> returned = new HashSet<>();
        for (Grant grant : snapshot.grants()) {
            returned.add(grant.id());
            // An ordinary sync must not undo an intentional local disconnect.
            if (!explicitAuthorization && "DISCONNECTED".equals(existing.get(grant.id()))) { skipped++; continue; }
            try {
                storage.save(workspaceId, snapshot.id(), grant.id(), grant.name(), grant.token());
                saved++;
            } catch (Exception ex) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không lưu được kết nối Fanpage. Chưa áp dụng thay đổi của lần đồng bộ này.");
            }
        }
        int missing = (int) existing.keySet().stream().filter(id -> !returned.contains(id)).count();
        // Do not revoke old grants or rewrite existing jobs based on a list response.
        return new SyncResult(returned.size(), saved, skipped, missing);
    }

    private Snapshot fetch(String token) throws Exception {
        JsonNode me = get("/me", "id,name", null, token);
        String id = me.path("id").asText("");
        String name = me.path("name").asText("");
        if (id.isBlank() || id.length() > 128) throw new IllegalStateException("Missing Facebook identity");
        Map<String, Grant> grants = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        String after = null;
        // A safety bound rejects an incomplete snapshot; it never silently truncates the list.
        for (int page = 0; page < 1000; page++) {
            JsonNode response = get("/me/accounts", "id,name,access_token,tasks", after, token);
            JsonNode data = response.path("data");
            if (!data.isArray()) throw new IllegalStateException("Incomplete Page listing");
            for (JsonNode item : data) {
                String pageId = item.path("id").asText("");
                String pageName = item.path("name").asText("");
                String pageToken = item.path("access_token").asText("");
                boolean canPublish = false;
                for (JsonNode task : item.path("tasks")) if ("CREATE_CONTENT".equals(task.asText())) canPublish = true;
                if (canPublish && !pageId.isBlank() && !pageToken.isBlank())
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
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("Meta request failed");
        return json.readTree(response.body());
    }
}
