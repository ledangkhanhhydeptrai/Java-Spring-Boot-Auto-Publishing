package com.socialhub.post.entity;

import com.socialhub.excelimport.entity.ExcelImport;
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
@Table(name = "posts")
@Getter
@Setter
@NoArgsConstructor
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    public Workspace workspace;
    public String topic;
    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;
    @Column(nullable = false)
    public String status = "DRAFT";
    @Column(name = "scheduled_at")
    public OffsetDateTime scheduledAt;
    @Column(name = "created_at", insertable = false, updatable = false)
    public OffsetDateTime createdAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_id")
    private ExcelImport excelImport;
    @Column(name = "approval_status", nullable = false)
    private String approvalStatus = "NOT_SUBMITTED";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;
    @Column(name = "source_row_number")
    private Integer sourceRowNumber;
}
