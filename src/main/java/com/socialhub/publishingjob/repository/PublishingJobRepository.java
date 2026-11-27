
package com.socialhub.publishingjob.repository;

import com.socialhub.publishingjob.entity.PublishingJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PublishingJobRepository
        extends JpaRepository<PublishingJob, UUID> {

    List<PublishingJob> findByPostId(UUID postId);

    @Query(value = """
            SELECT j FROM PublishingJob j
            JOIN j.post p
            JOIN j.socialAccount a
            WHERE p.workspace.id = :workspaceId
              AND (:status = '' OR j.status = :status)
              AND (:platform = '' OR a.platform = :platform)
              AND (:postId IS NULL OR p.id = :postId)
            """, countQuery = """
            SELECT COUNT(j) FROM PublishingJob j
            JOIN j.post p
            JOIN j.socialAccount a
            WHERE p.workspace.id = :workspaceId
              AND (:status = '' OR j.status = :status)
              AND (:platform = '' OR a.platform = :platform)
              AND (:postId IS NULL OR p.id = :postId)
            """)
    Page<PublishingJob> searchWorkspaceJobs(
            @Param("workspaceId") UUID workspaceId,
            @Param("status") String status,
            @Param("postId") UUID postId,
            @Param("platform") String platform,
            Pageable pageable);

    default Page<PublishingJob> searchWorkspaceJobs(
            UUID workspaceId, String status, UUID postId, Pageable pageable) {
        return searchWorkspaceJobs(workspaceId, status, postId, "", pageable);
    }


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT j
            FROM PublishingJob j
            WHERE j.id = :jobId
            """)
    Optional<PublishingJob> findByIdForUpdate(
            @Param("jobId") UUID jobId
    );

    boolean existsByPostIdAndSocialAccountId(
            UUID postId,
            UUID socialAccountId
    );
}
