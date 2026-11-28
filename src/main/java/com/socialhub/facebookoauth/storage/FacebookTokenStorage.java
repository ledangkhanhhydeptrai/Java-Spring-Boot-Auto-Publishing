package com.socialhub.facebookoauth.storage;

import com.socialhub.facebookoauth.entity.FacebookPageConnection;
import com.socialhub.facebookoauth.repository.FacebookPageConnectionRepository;
import com.socialhub.socialaccount.entity.SocialAccount;
import com.socialhub.socialaccount.repository.SocialAccountRepository;
import com.socialhub.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FacebookTokenStorage {
    private static final String PREFIX = "aesgcm:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SocialAccountRepository accounts;
    private final WorkspaceRepository workspaces;
    private final FacebookPageConnectionRepository connections;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.facebook.token-key:}")
    private String tokenKey;

    /**
     * Legacy API, preserved for compatibility with any existing callers.
     */
    @Transactional
    public void save(UUID workspaceId, String pageId, String pageName, String pageToken) throws Exception {
        SocialAccount account = findOrCreatePage(workspaceId, pageId);
        account.displayName = pageName;
        account.status = "CONNECTED";
        account.accessTokenEncrypted = encrypt(pageToken);
        account.lastConnectedAt = OffsetDateTime.now(ZoneOffset.UTC);
        account.tokenExpiresAt = null;
        accounts.save(account);
    }

    /**
     * Separate a Facebook grantor from the target Page. Existing Page tokens are retained.
     */
    @Transactional
    public void save(UUID workspaceId, String facebookUserId, String pageId,
                     String pageName, String pageToken) throws Exception {
        if (facebookUserId == null || facebookUserId.isBlank()) {
            throw new IllegalArgumentException("Facebook user id must not be empty");
        }
        // Encrypt before any DB mutation so missing/malformed key fails atomically.
        String encryptedToken = encrypt(pageToken);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        SocialAccount account = findOrCreatePage(workspaceId, pageId);
        account.displayName = pageName;
        account.status = "CONNECTED";
        account.lastConnectedAt = now;
        if (account.accessTokenEncrypted == null || account.accessTokenEncrypted.isBlank()) {
            // Bootstrap new Pages only; never overwrite an existing Page's working token.
            account.accessTokenEncrypted = encryptedToken;
            account.tokenExpiresAt = null;
        }
        account = accounts.saveAndFlush(account);

        FacebookPageConnection connection = connections
                .findBySocialAccountIdAndFacebookUserId(account.id, facebookUserId)
                .orElseGet(FacebookPageConnection::new);
        connection.setSocialAccount(account);
        connection.setFacebookUserId(facebookUserId);
        connection.setAccessTokenEncrypted(encryptedToken);
        connection.setStatus("CONNECTED");
        connection.setLastConnectedAt(now);
        // Meta /me/accounts response does not provide a reliable expiry here.
        connection.setTokenExpiresAt(null);
        connections.save(connection);
    }

    private SocialAccount findOrCreatePage(UUID workspaceId, String pageId) {
        SocialAccount account = accounts.findByWorkspaceIdAndPlatformAndExternalAccountId(
                workspaceId, "FACEBOOK_PAGE", pageId).orElseGet(SocialAccount::new);
        if (account.id == null) {
            account.workspace = workspaces.findById(workspaceId).orElseThrow();
            account.platform = "FACEBOOK_PAGE";
            account.externalAccountId = pageId;
        }
        return account;
    }

    /**
     * Existing publisher contract: keep the known working Page token selection unchanged.
     */
    @Transactional(readOnly = true)
    public String getPageAccessToken(UUID workspaceId, String pageId) throws Exception {
        SocialAccount account = accounts.findByWorkspaceIdAndPlatformAndExternalAccountId(
                        workspaceId, "FACEBOOK_PAGE", pageId)
                .orElseThrow(() -> new IllegalStateException("Facebook Page account not found"));
        if (!"CONNECTED".equals(account.status)) {
            throw new IllegalStateException("Facebook Page account is not connected");
        }
        if (account.tokenExpiresAt != null &&
                !account.tokenExpiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalStateException("Facebook Page token has expired");
        }
        if (account.accessTokenEncrypted == null || account.accessTokenEncrypted.isBlank()) {
            throw new IllegalStateException("Facebook Page access token is missing");
        }
        return decrypt(account.accessTokenEncrypted);
    }

    /**
     * Return the Page token granted by one explicitly selected Facebook person.
     * Never fall back to another grantor: an invalid grant must fail before Meta is called.
     * The original two-argument getPageAccessToken remains unchanged for legacy publishing.
     */
    @Transactional(readOnly = true)
    public String getPageAccessToken(UUID workspaceId, String pageId,
                                     String facebookUserId) throws Exception {
        if (facebookUserId == null || facebookUserId.isBlank()) {
            throw new IllegalArgumentException("Facebook user ID is required for connection selection");
        }
        SocialAccount account = accounts.findByWorkspaceIdAndPlatformAndExternalAccountId(
                        workspaceId, "FACEBOOK_PAGE", pageId)
                .orElseThrow(() -> new IllegalStateException("Facebook Page account not found"));
        if (!"CONNECTED".equals(account.status)) {
            throw new IllegalStateException("Facebook Page account is not connected");
        }

        FacebookPageConnection connection = connections
                .findBySocialAccountIdAndFacebookUserId(account.id, facebookUserId.trim())
                .orElseThrow(() -> new IllegalStateException(
                        "Selected Facebook user has no connection to this Page"));
        if (!"CONNECTED".equals(connection.getStatus())) {
            throw new IllegalStateException("Selected Facebook connection is not connected");
        }
        OffsetDateTime expires = connection.getTokenExpiresAt();
        if (expires != null && !expires.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalStateException("Selected Facebook Page token has expired");
        }
        String encrypted = connection.getAccessTokenEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            throw new IllegalStateException("Selected Facebook Page access token is missing");
        }
        return decrypt(encrypted);
    }

    /**
     * Strict binding: the token must belong to the exact Page and Workspace of this Job.
     */
    @Transactional(readOnly = true)
    public String getPageAccessToken(UUID workspaceId, String pageId, UUID connectionId) throws Exception {
        SocialAccount account = accounts.findByWorkspaceIdAndPlatformAndExternalAccountId(
                        workspaceId, "FACEBOOK_PAGE", pageId)
                .orElseThrow(() -> new IllegalStateException("Facebook Page account not found"));
        if (!"CONNECTED".equals(account.getStatus())) {
            throw new IllegalStateException("Facebook Page account is not connected");
        }
        FacebookPageConnection connection = connections.findById(connectionId)
                .orElseThrow(() -> new IllegalStateException("Bound Facebook connection not found"));
        if (!account.getId().equals(connection.getSocialAccount().getId())
                || !"CONNECTED".equals(connection.getStatus())) {
            throw new IllegalStateException("Bound Facebook connection is invalid for this Page");
        }
        OffsetDateTime expires = connection.getTokenExpiresAt();
        if (expires != null && !expires.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalStateException("Bound Facebook Page token has expired");
        }
        String encrypted = connection.getAccessTokenEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            throw new IllegalStateException("Bound Facebook Page token is missing");
        }
        return decrypt(encrypted);
    }

    /**
     * Encrypt a user grant using the same configured key; never return it in an API response.
     */
    public String encryptUserToken(String plaintext) throws Exception {
        return encrypt(plaintext);
    }

    public String decryptUserToken(String encrypted) throws Exception {
        return decrypt(encrypted);
    }

    private String encrypt(String plaintext) throws Exception {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Facebook access token must not be empty");
        }
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey(), "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
        return PREFIX + Base64.getEncoder().encodeToString(packed);
    }

    private String decrypt(String encrypted) throws Exception {
        if (!encrypted.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Unsupported Facebook token encryption format");
        }
        byte[] packed = Base64.getDecoder().decode(encrypted.substring(PREFIX.length()));
        if (packed.length < IV_LENGTH + 17) {
            throw new IllegalArgumentException("Invalid encrypted Facebook token");
        }
        byte[] iv = Arrays.copyOfRange(packed, 0, IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(packed, IV_LENGTH, packed.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey(), "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private byte[] encryptionKey() {
        byte[] key = Base64.getDecoder().decode(tokenKey);
        if (key.length != 32) {
            throw new IllegalArgumentException(
                    "FACEBOOK_TOKEN_ENCRYPTION_KEY must be a Base64 encoded 32-byte key");
        }
        return key;
    }
}
