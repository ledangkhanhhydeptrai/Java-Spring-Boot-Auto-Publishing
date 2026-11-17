
package com.socialhub.post.repository;

import com.socialhub.post.entity.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID> {

    List<Post> findByWorkspaceId(UUID workspaceId);


    @Query(
            value = """
                    SELECT p
                    FROM Post p
                    WHERE p.workspace.id = :workspaceId
                      AND (
                          :status = ''
                          OR p.status = :status
                      )
                      AND (
                          :search = ''
                          OR LOWER(COALESCE(p.topic, ''))
                             LIKE CONCAT('%', LOWER(:search), '%')
                          OR LOWER(p.content)
                             LIKE CONCAT('%', LOWER(:search), '%')
                      )
                    """,
            countQuery = """
                    SELECT COUNT(p)
                    FROM Post p
                    WHERE p.workspace.id = :workspaceId
                      AND (
                          :status = ''
                          OR p.status = :status
                      )
                      AND (
                          :search = ''
                          OR LOWER(COALESCE(p.topic, ''))
                             LIKE CONCAT('%', LOWER(:search), '%')
                          OR LOWER(p.content)
                             LIKE CONCAT('%', LOWER(:search), '%')
                      )
                    """
    )
    Page<Post> searchPosts(
            @Param("workspaceId") UUID workspaceId,
            @Param("status") String status,
            @Param("search") String search,
            Pageable pageable
    );


    @Query(value = """
            SELECT (
                EXISTS (
                    SELECT 1
                    FROM publishing_jobs j
                    WHERE j.post_id = :postId
                )
                OR EXISTS (
                    SELECT 1
                    FROM group_publishing_tasks t
                    WHERE t.post_id = :postId
                )
            )
            """, nativeQuery = true)
    boolean hasPublishingTasks(@Param("postId") UUID postId);
}
