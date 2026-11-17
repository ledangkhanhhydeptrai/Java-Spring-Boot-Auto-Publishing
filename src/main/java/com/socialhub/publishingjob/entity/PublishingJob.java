package com.socialhub.publishingjob.entity;

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
@Table(name = "publishing_jobs")
@Getter
@Setter
@NoArgsConstructor
public class PublishingJob {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    public Post post;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "social_account_id", nullable = false)
    public SocialAccount socialAccount;
    // Optional, immutable selection of the Page grantor for a publishing job.
    // Older rows remain null and preserve the existing legacy publisher path.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "facebook_connection_id")
    private com.socialhub.facebookoauth.entity.FacebookPageConnection facebookConnection;
    @Column(nullable = false)
    public String status = "PENDING";
    @Column(name = "attempt_count", nullable = false)
    public Integer attemptCount = 0;
    @Column(name = "scheduled_at")
    public OffsetDateTime scheduledAt;
    @Column(name = "published_at")
    public OffsetDateTime publishedAt;
    @Column(name = "external_post_id")
    public String externalPostId;
    @Column(name = "published_url", columnDefinition = "TEXT")
    public String publishedUrl;
    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;
    @Column(name = "next_retry_at")
    public OffsetDateTime nextRetryAt;
    @Column(name = "processing_started_at")
    public OffsetDateTime processingStartedAt;
    @Column(name = "locked_until")
    public OffsetDateTime lockedUntil;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
}
