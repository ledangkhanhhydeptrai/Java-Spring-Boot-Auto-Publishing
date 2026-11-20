package com.socialhub.grouppublishingtask.entity;

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
@Table(name = "group_publishing_tasks")
@Getter
@Setter
@NoArgsConstructor
public class GroupPublishingTask {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    public Post post;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "facebook_group_id", nullable = false)
    public FacebookGroup facebookGroup;
    @Column(nullable = false)
    public String status = "PENDING";
    @Column(name = "scheduled_at")
    public OffsetDateTime scheduledAt;
    @Column(name = "published_at")
    public OffsetDateTime publishedAt;
    @Column(name = "published_url", columnDefinition = "TEXT")
    public String publishedUrl;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
}
