package com.socialhub.facebookoauth.entity;

import com.socialhub.socialaccount.entity.SocialAccount;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "facebook_page_connections", uniqueConstraints = @UniqueConstraint(
        name = "uq_facebook_page_connection_user",
        columnNames = {"social_account_id", "facebook_user_id"}))
@Getter
@Setter
@NoArgsConstructor
public class FacebookPageConnection {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "social_account_id", nullable = false)
    private SocialAccount socialAccount;

    @Column(name = "facebook_user_id", nullable = false, length = 128)
    private String facebookUserId;

    @Column(name = "access_token_encrypted", nullable = false, columnDefinition = "TEXT")
    private String accessTokenEncrypted;

    @Column(nullable = false, length = 32)
    private String status = "CONNECTED";

    @Column(name = "last_connected_at", nullable = false)
    private OffsetDateTime lastConnectedAt;

    @Column(name = "token_expires_at")
    private OffsetDateTime tokenExpiresAt;
}
