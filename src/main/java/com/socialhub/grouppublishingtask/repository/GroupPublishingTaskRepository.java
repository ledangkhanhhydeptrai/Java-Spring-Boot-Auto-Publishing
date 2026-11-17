package com.socialhub.grouppublishingtask.repository;

import org.springframework.data.jpa.repository.JpaRepository; import java.util.*; import com.socialhub.grouppublishingtask.entity.GroupPublishingTask;
public interface GroupPublishingTaskRepository extends JpaRepository<GroupPublishingTask, UUID> { List<GroupPublishingTask> findByPostId(UUID postId); }
