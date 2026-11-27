package com.socialhub.grouppublishingtask.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

import com.socialhub.grouppublishingtask.entity.GroupPublishingTask;

public interface GroupPublishingTaskRepository extends JpaRepository<GroupPublishingTask, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from GroupPublishingTask t where t.id = :taskId and t.post.id = :postId")
    Optional<GroupPublishingTask> lockForStatusChange(
            @org.springframework.data.repository.query.Param("taskId") UUID taskId,
            @org.springframework.data.repository.query.Param("postId") UUID postId);

    List<GroupPublishingTask> findByPostId(UUID postId);
}
