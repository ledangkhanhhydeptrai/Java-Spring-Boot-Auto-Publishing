package com.socialhub.facebookoauth.service.implement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.facebookoauth.permission.FacebookOAuthPermission;
import com.socialhub.facebookoauth.service.interfaces.FacebookOAuthService;
import com.socialhub.facebookoauth.storage.FacebookTokenStorage;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FacebookOAuthServiceImpl implements FacebookOAuthService {

    private static final Logger log =
            LoggerFactory.getLogger(FacebookOAuthServiceImpl.class);

    private static final String STATE_COOKIE = "facebook_oauth_state";
    private static final long STATE_TTL = 600;

    private final FacebookOAuthPermission permission;
    private final FacebookTokenStorage storage;
    private final ObjectMapper json;

    private final SecureRandom random = new SecureRandom();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Value("${app.facebook.app-id:}")
    private String appId;

    @Value("${app.facebook.app-secret:}")
    private String appSecret;

    @Value("${app.facebook.redirect-uri:}")
    private String redirectUri;

    @Value("${app.facebook.state-secret:}")
    private String stateSecret;

    @Value("${app.facebook.token-key:}")
    private String tokenKey;

    @Value("${app.facebook.graph-version:v23.0}")
    private String graphVersion;

    @Value("${app.facebook.frontend-return-url:http://localhost:5173/social-accounts}")
    private String frontendReturnUrl;

    @Override
    public ResponseEntity<Void> start(UUID workspaceId, UUID userId) {

        checkConfig();

        if (!permission.canAdmin(workspaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        byte[] nonceBytes = new byte[32];
        random.nextBytes(nonceBytes);

        String nonce = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(nonceBytes);

        String payload = workspaceId + ":" + userId + ":"
                + Instant.now().getEpochSecond() + ":" + nonce;

        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        String state = encoded + "." + sign(encoded);

        String bridge = URI.create(redirectUri)
                .resolve("/api/facebook/oauth/bridge")
                .toString();

        log.info(
                "[FACEBOOK OAUTH] START | workspaceId={} | bridgeHost={}",
                workspaceId,
                URI.create(bridge).getHost()
        );

        return redirect(bridge + "?state=" + enc(state));
    }

    @Override
    public ResponseEntity<Void> bridge(
            String state,
            HttpServletResponse response
    ) {

        checkConfig();

        boolean signatureValid = validState(state);

        log.info(
                "[FACEBOOK OAUTH] BRIDGE | statePresent={} | signatureValid={} | callbackHost={}",
                state != null && !state.isBlank(),
                signatureValid,
                URI.create(redirectUri).getHost()
        );

        if (!signatureValid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid OAuth state"
            );
        }

        addStateCookie(response, stateCookieName(state), state, (int) STATE_TTL);

        String url = UriComponentsBuilder
                .fromUriString(
                        "https://www.facebook.com/"
                                + graphVersion
                                + "/dialog/oauth"
                )
                .queryParam("client_id", appId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam(
                        "scope",
                        "pages_show_list,pages_manage_posts,pages_read_engagement"
                )
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();

        log.info(
                "[FACEBOOK OAUTH] BRIDGE | State cookie configured | redirecting to Facebook"
        );

        return redirect(url);
    }

    @Override
    public ResponseEntity<Void> callback(
            String code,
            String state,
            String error,
            HttpServletRequest request,
            HttpServletResponse response
    ) {

        String cookieState = null;
        String cookieName = (state != null && !state.isBlank())
                ? stateCookieName(state)
                : null;

        if (cookieName != null && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookieName.equals(cookie.getName())) {
                    cookieState = cookie.getValue();
                    break;
                }
            }
        }

        // Only clear the cookie belonging to this OAuth attempt.
        if (cookieName != null) {
            addStateCookie(response, cookieName, "", 0);
        }

        if (error != null || code == null || code.isBlank()) {

            log.warn(
                    "[FACEBOOK OAUTH] CALLBACK | OAuth denied or authorization code missing"
            );

            return back("denied");
        }

        // Diagnostic information only:
        // Never log state, cookies, authorization codes, or tokens.
        boolean statePresent =
                state != null && !state.isBlank();

        boolean cookiePresent =
                cookieState != null && !cookieState.isBlank();

        boolean cookieMatches =
                statePresent
                        && cookiePresent
                        && MessageDigest.isEqual(
                        state.getBytes(StandardCharsets.UTF_8),
                        cookieState.getBytes(StandardCharsets.UTF_8)
                );

        boolean signatureValid =
                statePresent && validState(state);

        log.info(
                "[FACEBOOK OAUTH] CALLBACK | statePresent={} | cookiePresent={} | cookieMatches={} | signatureValid={} | host={} | secure={}",
                statePresent,
                cookiePresent,
                cookieMatches,
                signatureValid,
                request.getServerName(),
                request.isSecure()
        );

        if (!cookieMatches || !signatureValid) {

            log.warn(
                    "[FACEBOOK OAUTH] CALLBACK | INVALID_STATE | cookiePresent={} | cookieMatches={} | signatureValid={}",
                    cookiePresent,
                    cookieMatches,
                    signatureValid
            );

            return back("invalid_state");
        }

        try {

            String payload = state.split("\\.")[0];

            String decoded = new String(
                    Base64.getUrlDecoder().decode(payload),
                    StandardCharsets.UTF_8
            );

            String[] values = decoded.split(":");

            UUID workspaceId = UUID.fromString(values[0]);
            UUID userId = UUID.fromString(values[1]);

            if (!permission.canAdmin(workspaceId, userId)) {

                log.warn(
                        "[FACEBOOK OAUTH] CALLBACK | Access denied for workspace={}",
                        workspaceId
                );

                return back("access_denied");
            }

            checkConfig();

            String tokenUrl = UriComponentsBuilder
                    .fromUriString(
                            "https://graph.facebook.com/"
                                    + graphVersion
                                    + "/oauth/access_token"
                    )
                    .queryParam("client_id", appId)
                    .queryParam("client_secret", appSecret)
                    .queryParam("redirect_uri", redirectUri)
                    .queryParam("code", code)
                    .build()
                    .encode()
                    .toUriString();

            JsonNode tokenResponse = getJson(tokenUrl, null);

            String userToken = tokenResponse
                    .path("access_token")
                    .asText("");

            if (userToken.isBlank()) {

                log.warn(
                        "[FACEBOOK OAUTH] CALLBACK | User access token missing"
                );

                return back("token_missing");
            }

            // Exchange for a long-lived User Access Token.
            String longTokenUrl = UriComponentsBuilder
                    .fromUriString(
                            "https://graph.facebook.com/"
                                    + graphVersion
                                    + "/oauth/access_token"
                    )
                    .queryParam("grant_type", "fb_exchange_token")
                    .queryParam("client_id", appId)
                    .queryParam("client_secret", appSecret)
                    .queryParam("fb_exchange_token", userToken)
                    .build()
                    .encode()
                    .toUriString();

            JsonNode longTokenResponse =
                    getJson(longTokenUrl, null);

            String longToken = longTokenResponse
                    .path("access_token")
                    .asText("");

            if (longToken.isBlank()) {

                log.warn(
                        "[FACEBOOK OAUTH] CALLBACK | Long-lived token exchange failed"
                );

                return back("long_token_failed");
            }

            // Identity of the Facebook person who granted this authorization.
            // The userId in OAuth state is a SocialHub user, NOT a Meta user.
            String meUrl = UriComponentsBuilder
                    .fromUriString("https://graph.facebook.com/" + graphVersion + "/me")
                    .queryParam("fields", "id")
                    .build().encode().toUriString();
            String facebookUserId = getJson(meUrl, longToken).path("id").asText("");
            if (facebookUserId.isBlank()) {
                return back("facebook_user_missing");
            }

            String pagesUrl = UriComponentsBuilder
                    .fromUriString(
                            "https://graph.facebook.com/"
                                    + graphVersion
                                    + "/me/accounts"
                    )
                    .queryParam(
                            "fields",
                            "id,name,access_token,tasks"
                    )
                    .queryParam("limit", 100)
                    .build()
                    .encode()
                    .toUriString();

            JsonNode pagesResponse = getJson(
                    pagesUrl,
                    longToken
            );

            JsonNode pages = pagesResponse.path("data");

            if (!pages.isArray() || pages.isEmpty()) {

                log.warn(
                        "[FACEBOOK OAUTH] CALLBACK | No Facebook Pages returned"
                );

                return back("no_pages");
            }

            int connected = 0;

            for (JsonNode page : pages) {

                String pageId =
                        page.path("id").asText("");

                String pageName =
                        page.path("name").asText("");

                String pageToken =
                        page.path("access_token").asText("");

                if (pageId.isBlank() || pageToken.isBlank()) {
                    continue;
                }

                // Require the Page task used for publishing.
                boolean canCreateContent = false;

                JsonNode tasks = page.path("tasks");

                if (tasks.isArray()) {

                    for (JsonNode task : tasks) {

                        if ("CREATE_CONTENT".equals(task.asText())) {
                            canCreateContent = true;
                            break;
                        }
                    }
                }

                if (!canCreateContent) {
                    continue;
                }

                storage.save(
                        workspaceId,
                        facebookUserId,
                        pageId,
                        pageName.isBlank() ? pageId : pageName,
                        pageToken
                );

                connected++;

                log.info(
                        "[FACEBOOK OAUTH] CALLBACK | Page connected | workspaceId={} | pageId={}",
                        workspaceId,
                        pageId
                );
            }

            log.info(
                    "[FACEBOOK OAUTH] CALLBACK | Finished | connectedPages={}",
                    connected
            );

            return back(
                    connected > 0
                            ? "connected"
                            : "no_publishable_pages"
            );

        } catch (Exception e) {

            // Never expose authorization codes, access tokens,
            // raw Meta responses, or complete request URLs.
            log.error(
                    "[FACEBOOK OAUTH] CALLBACK | Connection failed | exceptionType={}",
                    e.getClass().getSimpleName()
            );

            return back("connection_failed");
        }
    }

    private JsonNode getJson(
            String url,
            String bearerToken
    ) throws Exception {

        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET();

        if (bearerToken != null && !bearerToken.isBlank()) {

            builder.header(
                    "Authorization",
                    "Bearer " + bearerToken
            );
        }

        HttpResponse<String> result = http.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (result.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "Meta API request failed"
            );
        }

        return json.readTree(result.body());
    }

    private boolean validState(String state) {

        try {

            if (state == null || state.isBlank()) {
                return false;
            }

            String[] parts = state.split("\\.");

            if (parts.length != 2) {
                return false;
            }

            if (!MessageDigest.isEqual(
                    sign(parts[0]).getBytes(StandardCharsets.UTF_8),
                    parts[1].getBytes(StandardCharsets.UTF_8))) {
                return false;
            }

            String decoded = new String(
                    Base64.getUrlDecoder().decode(parts[0]),
                    StandardCharsets.UTF_8
            );

            String[] values = decoded.split(":");

            if (values.length != 4) {
                return false;
            }

            UUID.fromString(values[0]);
            UUID.fromString(values[1]);

            long createdAt = Long.parseLong(values[2]);

            long age =
                    Instant.now().getEpochSecond() - createdAt;

            return age >= 0 && age <= STATE_TTL;

        } catch (Exception e) {
            return false;
        }
    }

    private String sign(String value) {

        try {

            Mac mac = Mac.getInstance("HmacSHA256");

            mac.init(
                    new SecretKeySpec(
                            stateSecret.getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"
                    )
            );

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            mac.doFinal(
                                    value.getBytes(StandardCharsets.UTF_8)
                            )
                    );

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Cannot sign OAuth state",
                    e
            );
        }
    }

    private void checkConfig() {

        if (appId.isBlank()
                || appSecret.isBlank()
                || redirectUri.isBlank()
                || stateSecret.isBlank()
                || tokenKey.isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Facebook OAuth configuration is missing"
            );
        }
    }

    private String stateCookieName(String state) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(state.getBytes(StandardCharsets.UTF_8));
            return STATE_COOKIE + "_" + HexFormat.of().formatHex(hash, 0, 12);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot derive OAuth state cookie name", e);
        }
    }

    private void addStateCookie(
            HttpServletResponse response,
            String cookieName,
            String value,
            int maxAge
    ) {
        response.addHeader(
                "Set-Cookie",
                cookieName + "=" + value
                        + "; Path=/api/facebook/oauth"
                        + "; Max-Age=" + maxAge
                        + "; HttpOnly; Secure; SameSite=Lax"
        );
    }

    private ResponseEntity<Void> back(String status) {

        String separator =
                frontendReturnUrl.contains("?") ? "&" : "?";

        return redirect(
                frontendReturnUrl
                        + separator
                        + "facebook="
                        + enc(status)
        );
    }

    private ResponseEntity<Void> redirect(String url) {

        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(url))
                .build();
    }

    private static String enc(String value) {

        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }
}