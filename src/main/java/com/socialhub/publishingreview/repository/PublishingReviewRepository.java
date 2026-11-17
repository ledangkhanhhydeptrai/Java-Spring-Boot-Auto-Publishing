
package com.socialhub.publishingreview.repository;

import com.socialhub.publishingreview.entity.PublishingReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PublishingReviewRepository
        extends JpaRepository<PublishingReview, UUID> {

    List<PublishingReview>
    findByPublishingJob_IdOrderByCreatedAtDesc(UUID publishingJobId);
    Optional<PublishingReview>
    findByPublishingJob_IdAndAttemptNumber(
            UUID publishingJobId,
            Integer attemptNumber
    );
}
