package com.socialhub.postmedia.entity;

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
@Table(name = "post_media")
@Getter
@Setter
@NoArgsConstructor
public class PostMedia {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    public Post post;
    @Column(name = "source_url", nullable = false, columnDefinition = "TEXT")
    public String sourceUrl;
    @Column(name = "public_url", columnDefinition = "TEXT")
    public String publicUrl;
    @Column(name = "media_type", nullable = false)
    public String mediaType = "IMAGE";
    @Column(name = "display_order", nullable = false)
    public Integer displayOrder = 0;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
}
