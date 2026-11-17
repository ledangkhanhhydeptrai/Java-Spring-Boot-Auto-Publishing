package com.socialhub.threads;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.security.AccessService;
import com.socialhub.socialaccount.entity.SocialAccount;
import com.socialhub.socialaccount.repository.SocialAccountRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** Threads authorization-code OAuth. The callback is public but requires a signed, short-lived state and a matching HttpOnly cookie. */
@RestController
@RequestMapping("/api/threads/oauth")
@RequiredArgsConstructor
public class ThreadsOAuthController {
    private static final String STATE_COOKIE = "threads_oauth_state";
    private static final long STATE_TTL_SECONDS = 600;
    private final AccessService access;
    private final SocialAccountRepository accounts;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Value("${app.threads.app-id:}") private String appId;
    @Value("${app.threads.app-secret:}") private String appSecret;
    @Value("${app.threads.redirect-uri:}") private String redirectUri;
    @Value("${app.threads.state-secret:}") private String stateSecret;
    @Value("${app.threads.token-key:}") private String tokenKey;
    @Value("${app.threads.frontend-return-url:http://localhost:5173/social-accounts}") private String frontendReturnUrl;

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam UUID workspaceId, HttpServletResponse response) {
        access.admin(workspaceId);
        checkConfig();
        String nonce = randomString(32);
        String payload = workspaceId + ":" + access.userId() + ":" + Instant.now().getEpochSecond() + ":" + nonce;
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String state = encoded + "." + sign(encoded);
        String bridge = URI.create(redirectUri).resolve("/api/threads/oauth/bridge").toString();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(bridge + "?state=" + enc(state))).build();
    }

    @GetMapping("/bridge")
    public ResponseEntity<Void> bridge(@RequestParam String state, HttpServletResponse response) {
        checkConfig();
        if (!validState(state)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OAuth state");
        addStateCookie(response, state, 600);
        String url = UriComponentsBuilder.fromUriString("https://threads.net/oauth/authorize")
                .queryParam("client_id", appId).queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "threads_basic,threads_content_publish")
                .queryParam("response_type", "code").queryParam("state", state)
                .build().encode().toUriString();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    private boolean validState(String state) {
        try {
            String[] parts = state.split("\\.");
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) return false;
            String[] values = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split(":");
            return values.length == 4 && Math.abs(Instant.now().getEpochSecond() - Long.parseLong(values[2])) <= STATE_TTL_SECONDS;
        } catch (Exception e) { return false; }
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         HttpServletRequest request, HttpServletResponse response) {
        String cookie = null;
        if (request.getCookies() != null) for (Cookie c : request.getCookies())
            if (STATE_COOKIE.equals(c.getName())) cookie = c.getValue();
        addStateCookie(response, "", 0);
        if (error != null || code == null || code.isBlank()) return back("denied");
        if (state == null || cookie == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), cookie.getBytes(StandardCharsets.UTF_8)))
            return back("invalid_state");
        try {
            String[] pieces = state.split("\\.");
            if (pieces.length != 2 || !MessageDigest.isEqual(sign(pieces[0]).getBytes(StandardCharsets.UTF_8), pieces[1].getBytes(StandardCharsets.UTF_8)))
                return back("invalid_state");
            String[] values = new String(Base64.getUrlDecoder().decode(pieces[0]), StandardCharsets.UTF_8).split(":");
            if (values.length != 4 || Math.abs(Instant.now().getEpochSecond() - Long.parseLong(values[2])) > STATE_TTL_SECONDS)
                return back("expired_state");
            UUID workspaceId = UUID.fromString(values[0]);
            UUID userId = UUID.fromString(values[1]);
            // The initiating user must still be an OWNER or ADMIN of the same workspace.
            // This validation does not depend on the auth cookie being sent to the ngrok domain.
            if (!permission.canAdmin(workspaceId, userId)) return back("access_denied");
            checkConfig();
            String form = "client_id=" + enc(appId) + "&client_secret=" + enc(appSecret) + "&grant_type=authorization_code&redirect_uri=" + enc(redirectUri) + "&code=" + enc(code);
            HttpRequest exchange = HttpRequest.newBuilder(URI.create("https://graph.threads.net/oauth/access_token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> result = http.send(exchange, HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() / 100 != 2) return back("token_exchange_failed");
            JsonNode token = json.readTree(result.body());
            String accessToken = token.path("access_token").asText("");
            if (accessToken.isBlank()) return back("token_missing");
            long expiresIn = token.path("expires_in").asLong(3600);
            // Exchange short-lived token for a long-lived token when supported.
            String longUrl = UriComponentsBuilder.fromUriString("https://graph.threads.net/access_token")
                    .queryParam("grant_type", "th_exchange_token")
                    .queryParam("client_secret", appSecret)
                    .queryParam("access_token", accessToken).build().encode().toUriString();
            HttpResponse<String> longer = http.send(HttpRequest.newBuilder(URI.create(longUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (longer.statusCode() / 100 == 2) {
                JsonNode longJson = json.readTree(longer.body());
                if (!longJson.path("access_token").asText("").isBlank()) {
                    accessToken = longJson.path("access_token").asText();
                    expiresIn = longJson.path("expires_in").asLong(5184000);
                }
            }
            String meUrl = UriComponentsBuilder.fromUriString("https://graph.threads.net/v1.0/me")
                    .queryParam("fields", "id,username,name")
                    .queryParam("access_token", accessToken).build().encode().toUriString();
            HttpResponse<String> profile = http.send(HttpRequest.newBuilder(URI.create(meUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (profile.statusCode() / 100 != 2) return back("profile_failed");
            JsonNode me = json.readTree(profile.body());
            String externalId = me.path("id").asText("");
            if (externalId.isBlank()) return back("profile_missing_id");
            String name = me.path("username").asText(externalId);
            storeAccount(workspaceId, externalId, name, accessToken, expiresIn);
            return back("connected");
        } catch (Exception e) {
            // Never include tokens, codes, secrets, or upstream response bodies in redirects.
            return back("connection_failed");
        }
    }

    private final ThreadsOAuthPermission permission;
    private final ThreadsTokenStorage storage;

    @org.springframework.transaction.annotation.Transactional
    protected void storeAccount(UUID workspaceId, String externalId, String name, String token, long expiresIn) throws Exception {
        storage.save(workspaceId, externalId, name, token, expiresIn);
    }

    private void checkConfig() {
        if (appId.isBlank() || appSecret.isBlank() || redirectUri.isBlank() || stateSecret.isBlank() || tokenKey.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Threads OAuth environment variables are missing");
    }
    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stateSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Cannot sign OAuth state", e); }
    }
    private String randomString(int length) { byte[] b = new byte[length]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }
    private void addStateCookie(HttpServletResponse response, String value, int maxAge) {
        response.addHeader("Set-Cookie", STATE_COOKIE + "=" + value + "; Path=/api/threads/oauth; Max-Age=" + maxAge + "; HttpOnly; Secure; SameSite=Lax");
    }
    private ResponseEntity<Void> back(String status) {
        String separator = frontendReturnUrl.contains("?") ? "&" : "?";
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(frontendReturnUrl + separator + "threads=" + enc(status))).build();
    }
}
