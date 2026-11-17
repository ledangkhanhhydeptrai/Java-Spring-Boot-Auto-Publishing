
package com.socialhub.postapproval.repository;

import com.socialhub.postapproval.entity.PostApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PostApprovalRepository
        extends JpaRepository<PostApproval, UUID> {

    List<PostApproval> findByPost_IdOrderByCreatedAtDesc(
            UUID postId
    );

    boolean existsByPost_Id(UUID postId);
}
