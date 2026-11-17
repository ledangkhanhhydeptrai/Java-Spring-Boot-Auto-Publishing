
package com.socialhub.publishingreview.entity;

import com.socialhub.publishingjob.entity.PublishingJob;
import com.socialhub.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "publishing_reviews")
@Getter
@Setter
@NoArgsConstructor
public class PublishingReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publishing_job_id", nullable = false)
    private PublishingJob publishingJob;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewed_by", nullable = false)
    private User reviewedBy;

    @Column(name = "decision", nullable = false, length = 30)
    private String decision;

    @Column(name = "evidence", nullable = false, columnDefinition = "TEXT")
    private String evidence;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "external_post_id", length = 255)
    private String externalPostId;

    @Column(name = "published_url", columnDefinition = "TEXT")
    private String publishedUrl;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;
}
