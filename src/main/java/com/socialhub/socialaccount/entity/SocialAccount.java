package com.socialhub.socialaccount.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.*;
import java.time.*;

import com.socialhub.user.entity.User;
import com.socialhub.workspace.entity.Workspace;
import com.socialhub.workspacemember.entity.WorkspaceMemberId;
import com.socialhub.post.entity.Post;
import com.socialhub.socialaccount.entity.SocialAccount;
import com.socialhub.publishingjob.entity.PublishingJob;
import com.socialhub.facebookgroup.entity.FacebookGroup;

@Entity
@Table(name = "social_accounts")
@Getter
@Setter
@NoArgsConstructor
public class SocialAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    public Workspace workspace;
    @Column(nullable = false)
    public String platform;
    @Column(name = "external_account_id", nullable = false)
    public String externalAccountId;
    @Column(name = "display_name", nullable = false)
    public String displayName;
    @Column(nullable = false)
    public String status = "DISCONNECTED";
    @Column(name = "access_token_encrypted", columnDefinition = "TEXT")
    public String accessTokenEncrypted;
    @Column(name = "token_expires_at")
    public OffsetDateTime tokenExpiresAt;
    @Column(name = "last_connected_at")
    public OffsetDateTime lastConnectedAt;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
}
