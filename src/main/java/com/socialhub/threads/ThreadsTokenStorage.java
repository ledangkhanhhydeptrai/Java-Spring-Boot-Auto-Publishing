package com.socialhub.threads;

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
import java.util.Base64;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class ThreadsTokenStorage {
    private final SocialAccountRepository accounts;
    private final WorkspaceRepository workspaces;
    @Value("${app.threads.token-key:}") private String tokenKey;
    @Transactional
    public void save(UUID workspaceId, String externalId, String name, String token, long expiresIn) throws Exception {
        SocialAccount account = accounts.findByWorkspaceIdAndPlatformAndExternalAccountId(workspaceId, "THREADS", externalId)
                .orElseGet(SocialAccount::new);
        if (account.id == null) {
            account.workspace = workspaces.findById(workspaceId).orElseThrow();
            account.platform = "THREADS";
            account.externalAccountId = externalId;
        }
        account.displayName = name;
        account.status = "CONNECTED";
        account.accessTokenEncrypted = encrypt(token);
        account.lastConnectedAt = OffsetDateTime.now(ZoneOffset.UTC);
        account.tokenExpiresAt = account.lastConnectedAt.plusSeconds(Math.max(0, expiresIn));
        accounts.save(account);
    }
    private String encrypt(String plaintext) throws Exception {
        byte[] key = Base64.getDecoder().decode(tokenKey);
        if (key.length != 32) throw new IllegalArgumentException("THREADS_TOKEN_ENCRYPTION_KEY must be a Base64 encoded 32-byte key");
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
        return "aesgcm:v1:" + Base64.getEncoder().encodeToString(packed);
    }
}
