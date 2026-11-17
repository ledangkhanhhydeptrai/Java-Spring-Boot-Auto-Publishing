
package com.socialhub.postapproval.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.post.entity.Post;
import com.socialhub.post.repository.PostRepository;
import com.socialhub.postapproval.dto.PostApprovalDto;
import com.socialhub.postapproval.entity.PostApproval;
import com.socialhub.postapproval.repository.PostApprovalRepository;
import com.socialhub.postapproval.service.interfaces.PostApprovalService;
import com.socialhub.security.AccessService;
import com.socialhub.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PostApprovalServiceImpl implements PostApprovalService {

    private final PostRepository posts;
    private final PostApprovalRepository approvals;
    private final AccessService access;
    private final EntityManager entityManager;

    private Post findPost(UUID postId) {
        return posts.findById(postId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Post not found"
                ));
    }

    /**
     * Lock Post without refreshing its state.
     * <p>
     * refresh() could overwrite unflushed approval changes
     * when submit() and approve() run in the same transaction.
     */
    private Post lockPost(UUID postId) {
        Post post = findPost(postId);

        access.admin(post.getWorkspace().getId());

        entityManager.lock(
                post,
                LockModeType.PESSIMISTIC_WRITE
        );

        return post;
    }

    private PostApprovalDto.Status statusView(Post post) {
        UUID approvedBy = post.getApprovedBy() == null
                ? null
                : post.getApprovedBy().getId();

        return new PostApprovalDto.Status(
                post.getId(),
                post.getStatus(),
                post.getApprovalStatus(),
                approvedBy,
                post.getApprovedAt()
        );
    }

    private PostApprovalDto.View historyView(PostApproval approval) {
        return new PostApprovalDto.View(
                approval.getId(),
                approval.getPost().getId(),
                approval.getReviewedBy().getId(),
                approval.getDecision(),
                approval.getNote(),
                approval.getCreatedAt()
        );
    }

    private void saveHistory(
            Post post,
            User reviewer,
            String decision,
            String note
    ) {
        PostApproval approval = new PostApproval();

        approval.setPost(post);
        approval.setReviewedBy(reviewer);
        approval.setDecision(decision);
        approval.setNote(note);

        approvals.save(approval);
    }

    @Override
    @Transactional
    public PostApprovalDto.Status submit(UUID postId) {

        Post post = lockPost(postId);

        String current = post.getApprovalStatus();

        if (!"NOT_SUBMITTED".equals(current)
                && !"REJECTED".equals(current)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Post cannot be submitted for approval"
            );
        }

        if (!"DRAFT".equals(post.getStatus())
                && !"READY".equals(post.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Only DRAFT or READY posts can be submitted"
            );
        }

        if (post.getContent() == null
                || post.getContent().isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Post content is required"
            );
        }

        post.setApprovalStatus("PENDING_APPROVAL");
        post.setApprovedBy(null);
        post.setApprovedAt(null);

        posts.saveAndFlush(post);

        return statusView(post);
    }

    @Override
    @Transactional
    public PostApprovalDto.Status approve(UUID postId) {

        Post post = lockPost(postId);

        if (!"PENDING_APPROVAL".equals(
                post.getApprovalStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Post must be pending approval"
            );
        }

        if (post.getContent() == null
                || post.getContent().isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Post content is required"
            );
        }

        Long pendingMedia = entityManager.createQuery("""
                        SELECT COUNT(m)
                        FROM PostMedia m
                        WHERE m.post.id = :postId
                          AND (
                              m.publicUrl IS NULL
                              OR m.publicUrl = ''
                          )
                        """, Long.class)
                .setParameter("postId", postId)
                .getSingleResult();

        if (pendingMedia > 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Post has media that is not ready"
            );
        }

        UUID reviewerId = access.userId();

        User reviewer = entityManager.getReference(
                User.class,
                reviewerId
        );

        post.setApprovalStatus("APPROVED");
        post.setApprovedBy(reviewer);
        post.setApprovedAt(OffsetDateTime.now());

        posts.save(post);

        saveHistory(
                post,
                reviewer,
                "APPROVED",
                null
        );

        return statusView(post);
    }

    @Override
    @Transactional
    public PostApprovalDto.Status reject(
            UUID postId,
            PostApprovalDto.Reject request
    ) {

        Post post = lockPost(postId);

        if (!"PENDING_APPROVAL".equals(
                post.getApprovalStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Post must be pending approval"
            );
        }

        if (request == null
                || request.reason() == null
                || request.reason().isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Reject reason is required"
            );
        }

        UUID reviewerId = access.userId();

        User reviewer = entityManager.getReference(
                User.class,
                reviewerId
        );

        post.setApprovalStatus("REJECTED");
        post.setApprovedBy(null);
        post.setApprovedAt(null);

        posts.save(post);

        saveHistory(
                post,
                reviewer,
                "REJECTED",
                request.reason().trim()
        );

        return statusView(post);
    }

    @Override
    @Transactional(readOnly = true)
    public PostApprovalDto.Status getStatus(UUID postId) {

        Post post = findPost(postId);

        access.workspace(post.getWorkspace().getId());

        return statusView(post);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostApprovalDto.View> history(UUID postId) {

        Post post = findPost(postId);

        access.workspace(post.getWorkspace().getId());

        return approvals
                .findByPost_IdOrderByCreatedAtDesc(postId)
                .stream()
                .map(this::historyView)
                .toList();
    }
}
