package com.socialhub.publishingattempt.entity;

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
@Table(name = "publishing_attempts")
@Getter
@Setter
@NoArgsConstructor
public class PublishingAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "publishing_job_id", nullable = false)
    public PublishingJob publishingJob;
    @Column(name = "attempt_number", nullable = false)
    public Integer attemptNumber;
    @Column(nullable = false)
    public String status;
    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;
    @Column(name = "started_at", insertable = false, updatable = false)
    public OffsetDateTime startedAt;
    @Column(name = "finished_at")
    public OffsetDateTime finishedAt;
}
