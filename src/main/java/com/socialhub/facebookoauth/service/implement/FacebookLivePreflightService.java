package com.socialhub.facebookoauth.service.implement;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.facebookoauth.storage.FacebookTokenStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only verification of the exact Facebook Page token a publishing job would use.
 * Does not POST to /feed or /photos and does not expose token material.
 * Permission and task checks are intentionally tri-state: VERIFIED / BLOCKED / NOT_VERIFIED.
 */
@Service
@RequiredArgsConstructor
public class FacebookLivePreflightService {

    private static final String GRAPH_ROOT = "https://graph.facebook.com/";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    private final JdbcTemplate db;
    private final FacebookTokenStorage tokens;
    private final ObjectMapper json;

    @Value("${META_GRAPH_VERSION:v23.0}")
    private String graphVersion;

    @Value("${FACEBOOK_LIVE_CONNECTION_PAGE_ID:}")
    private String selectedPageId;

    @Value("${FACEBOOK_LIVE_CONNECTION_USER_ID:}")
    private String selectedUserId;

    @Value("${app.facebook.app-id:}")
    private String appId;

    @Value("${app.facebook.app-secret:}")
    private String appSecret;

    // Keep the original response contract for Swagger and existing frontend clients.
    public record Result(
            UUID jobId,
            String pageId,
            String tokenSource,
            boolean tokenReadable,
            boolean pageIdentityConfirmed,
            String pageName,
            String publishingPermission,
            String status,
            @JsonSerialize(using = ViDisplayTextSerializer.class) String message
    ) {
    }

    private enum Check {YES, NO, UNKNOWN}

    private record Verification(Check result, String reason) {
    }

    public Result checkJob(UUID jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID is required");
        }
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT j.facebook_connection_id,
                       a.workspace_id, a.external_account_id, a.platform,
                       a.status AS account_status
                FROM publishing_jobs j
                JOIN social_accounts a ON a.id = j.social_account_id
                WHERE j.id = ?
                """, jobId);
        if (rows.size() != 1) {
            return blocked(jobId, "", "NONE", false, false, "", "Publishing Job not found");
        }

        Map<String, Object> row = rows.getFirst();
        String pageId = (String) row.get("external_account_id");
        if (!"FACEBOOK_PAGE".equals(row.get("platform"))
                || !"CONNECTED".equals(row.get("account_status"))
                || pageId == null || !pageId.matches("[0-9]+")) {
            return blocked(jobId, pageId, "NONE", false, false, "", "Page account is not eligible");
        }
        if (graphVersion == null || !graphVersion.matches("v[0-9]+\\.[0-9]+")) {
            return blocked(jobId, pageId, "NONE", false, false, "", "Invalid Graph API version");
        }

        UUID workspaceId = (UUID) row.get("workspace_id");
        UUID connectionId = (UUID) row.get("facebook_connection_id");
        String source = connectionId == null ? "LEGACY_SOCIAL_ACCOUNT" : "BOUND_CONNECTION";
        String token;
        try {
            if (connectionId != null) {
                token = tokens.getPageAccessToken(workspaceId, pageId, connectionId);
            } else {
                boolean pageSelected = selectedPageId != null && !selectedPageId.isBlank();
                boolean userSelected = selectedUserId != null && !selectedUserId.isBlank();
                if (pageSelected != userSelected) {
                    return blocked(jobId, pageId, "NONE", false, false, "", "Facebook connection selector is incomplete");
                }
                if (pageSelected && pageId.equals(selectedPageId.trim())) {
                    token = tokens.getPageAccessToken(workspaceId, pageId, selectedUserId.trim());
                    source = "SELECTED_GRANTOR";
                } else {
                    token = tokens.getPageAccessToken(workspaceId, pageId);
                }
            }
            if (token == null || token.isBlank()) {
                return blocked(jobId, pageId, source, false, false, "", "No usable Page token");
            }
        } catch (Exception ex) {
            return blocked(jobId, pageId, source, false, false, "", "Unable to read selected Page token");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String pageName;
        try {
            JsonNode identity = graphGet(client,
                    GRAPH_ROOT + graphVersion + "/me?fields=id,name", token);
            if (!pageId.equals(identity.path("id").asText())) {
                return blocked(jobId, pageId, source, true, false, "",
                        "Token identifies a different Facebook entity");
            }
            pageName = identity.path("name").asText("");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return blocked(jobId, pageId, source, true, false, "", "Identity check interrupted");
        } catch (Exception ex) {
            return blocked(jobId, pageId, source, true, false, "", "Meta identity check failed");
        }

        Verification permission = checkManagePosts(client, token, pageId);
        Verification task = checkContentVerifiedAtOAuth(
                connectionId, workspaceId, pageId
        );

        // Only assert verified if BOTH distinct requirements were confirmed.
        if (permission.result() == Check.YES && task.result() == Check.YES) {
            return new Result(jobId, pageId, source, true, true, pageName,
                    "VERIFIED", "PERMISSIONS_VERIFIED",
                    "Meta reports pages_manage_posts and CREATE_CONTENT for this Page token. "
                            + "Publishing has not been tested; actual POST can still fail.");
        }
        if (permission.result() == Check.NO || task.result() == Check.NO) {
            return new Result(jobId, pageId, source, true, true, pageName,
                    "BLOCKED", "PERMISSION_BLOCKED",
                    "Required capability not granted: "
                            + (permission.result() == Check.NO ? "pages_manage_posts " : "")
                            + (task.result() == Check.NO ? "CREATE_CONTENT" : ""));
        }
        return new Result(jobId, pageId, source, true, true, pageName,
                "NOT_VERIFIED", "IDENTITY_VERIFIED",
                "Page identity confirmed; publishing permission cannot be fully verified. "
                        + permission.reason() + "; " + task.reason());
    }

    private Verification checkManagePosts(HttpClient client, String pageToken, String pageId) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            return new Verification(Check.UNKNOWN, "Meta App ID/Secret not configured for debug_token");
        }
        // Meta's documented debug_token endpoint uses input_token as a GET query parameter.
        // Never log its URL, request, exception text, or any response body.
        String uri = GRAPH_ROOT + graphVersion + "/debug_token?input_token="
                + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
        String appToken = appId + "|" + appSecret;
        try {
            JsonNode result = graphGet(client, uri, appToken).path("data");
            if (result.isMissingNode() || result.isNull()) {
                return new Verification(Check.UNKNOWN, "debug_token returned no metadata");
            }
            if (!result.path("is_valid").asBoolean(false)) {
                return new Verification(Check.NO, "Token invalid according to debug_token");
            }
            if (!appId.equals(result.path("app_id").asText())) {
                return new Verification(Check.NO, "Token belongs to a different Meta app");
            }
            long expiresAt = result.path("expires_at").asLong(0L);
            if (expiresAt > 0 && expiresAt <= Instant.now().getEpochSecond()) {
                return new Verification(Check.NO, "Token expired according to debug_token");
            }
            boolean scopePresent = hasString(result.path("scopes"), "pages_manage_posts");
            JsonNode granular = result.path("granular_scopes");
            boolean granularFound = false;
            boolean granularMatchesPage = false;
            if (granular.isArray()) {
                for (JsonNode item : granular) {
                    if ("pages_manage_posts".equals(item.path("scope").asText())) {
                        granularFound = true;
                        JsonNode targetIds = item.path("target_ids");
                        // No target_ids means no page-specific restriction was returned.
                        granularMatchesPage = !targetIds.isArray() || hasString(targetIds, pageId);
                        break;
                    }
                }
            }
            if (granularFound && !granularMatchesPage) {
                return new Verification(Check.NO, "pages_manage_posts not granted for target Page");
            }
            if (scopePresent || (granularFound && granularMatchesPage)) {
                return new Verification(Check.YES, "pages_manage_posts confirmed");
            }
            return new Verification(Check.NO, "pages_manage_posts missing from token metadata");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Verification(Check.UNKNOWN, "debug_token interrupted");
        } catch (Exception ex) {
            return new Verification(Check.UNKNOWN, "debug_token request unavailable");
        }
    }

    private Verification checkCreateContent(HttpClient client, String pageToken, String pageId) {
        try {
            // Page tasks can be unavailable to a particular token or API version.
            // An absent tasks field must not be interpreted as permission granted.
            JsonNode result = graphGet(client,
                    GRAPH_ROOT + graphVersion + "/" + pageId + "?fields=id,tasks", pageToken);
            if (!pageId.equals(result.path("id").asText())) {
                return new Verification(Check.UNKNOWN, "Page tasks response does not match Page ID");
            }
            JsonNode tasks = result.path("tasks");
            if (!tasks.isArray()) {
                return new Verification(Check.UNKNOWN, "Meta did not provide Page tasks");
            }
            return hasString(tasks, "CREATE_CONTENT")
                    ? new Verification(Check.YES, "CREATE_CONTENT confirmed")
                    : new Verification(Check.NO, "CREATE_CONTENT absent from returned tasks");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Verification(Check.UNKNOWN, "Page tasks check interrupted");
        } catch (Exception ex) {
            return new Verification(Check.UNKNOWN, "Page tasks not accessible with this token");
        }
    }

    private JsonNode graphGet(HttpClient client, String uri, String bearer)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .header("Authorization", "Bearer " + bearer)
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // Do not include response bodies or request URI in exceptions.
            throw new IllegalStateException("Meta GET returned HTTP " + response.statusCode());
        }
        if (response.body() == null || response.body().isBlank()) {
            throw new IllegalStateException("Meta GET returned empty body");
        }
        return json.readTree(response.body());
    }

    private static boolean hasString(JsonNode array, String value) {
        if (!array.isArray()) return false;
        for (JsonNode item : array) {
            if (value.equals(item.asText())) return true;
        }
        return false;
    }

    private Result blocked(UUID jobId, String pageId, String source,
                           boolean readable, boolean identified,
                           String pageName, String reason) {
        return new Result(jobId, pageId == null ? "" : pageId, source,
                readable, identified, pageName, "NOT_VERIFIED", "BLOCKED", reason);
    }

    private Verification checkContentVerifiedAtOAuth(
            UUID connectionId,
            UUID workspaceId,
            String pageId
    ) {
        if (connectionId == null) {
            return new Verification(
                    Check.UNKNOWN, "No bound OAuth connection"
            );
        }

        Boolean verified = db.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM facebook_page_connections c
                            JOIN social_accounts a
                                ON a.id = c.social_account_id
                            WHERE c.id = ?
                              AND a.workspace_id = ?
                              AND a.external_account_id = ?
                              AND a.platform = 'FACEBOOK_PAGE'
                              AND a.status = 'CONNECTED'
                              AND c.status = 'CONNECTED'
                              AND c.last_connected_at >= NOW() - INTERVAL '24 hours'
                              AND (
                                  c.token_expires_at IS NULL
                                  OR c.token_expires_at > NOW()
                              )
                        )
                        """,
                Boolean.class,
                connectionId, workspaceId, pageId
        );

        return Boolean.TRUE.equals(verified)
                ? new Verification(
                Check.YES,
                "CREATE_CONTENT checked during recent OAuth connection"
        )
                : new Verification(
                Check.UNKNOWN,
                "Recent OAuth CREATE_CONTENT proof unavailable"
        );
    }
}
