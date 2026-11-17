package com.socialhub.user.entity;

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
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;
    @Column(nullable = false, unique = true)
    public String email;
    @Column(name = "password_hash", nullable = false)
    public String passwordHash;
    @Column(name = "display_name", nullable = false)
    public String displayName;
    @Column(nullable = false)
    public String role = "USER";
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
}
