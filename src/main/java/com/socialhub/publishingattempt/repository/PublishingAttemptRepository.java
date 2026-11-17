package com.socialhub.publishingattempt.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

import com.socialhub.publishingattempt.entity.PublishingAttempt;

public interface PublishingAttemptRepository extends JpaRepository<PublishingAttempt, UUID> {
    List<PublishingAttempt> findByPublishingJobId(UUID publishingJobId);
}
