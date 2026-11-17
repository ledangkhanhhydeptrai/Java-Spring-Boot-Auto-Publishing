
package com.socialhub.post.service.implement;

import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import com.socialhub.post.dto.PostDto;
import com.socialhub.post.entity.Post;
import com.socialhub.post.repository.PostRepository;
import com.socialhub.post.service.interfaces.PostService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final PostRepository repo;
    private final AccessService access;
    private final EntityManager entityManager;

    private PostDto.View view(Post e) {
        return new PostDto.View(
                e.id,
                e.topic,
                e.content,
                e.status,
                e.scheduledAt
        );
    }

    private Post findPost(UUID workspaceId, UUID id) {
        Post post = repo.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Post not found"
                ));

        if (!post.getWorkspace().getId().equals(workspaceId)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "Post not found"
            );
        }

        return post;
    }

    /**
     * Đồng bộ khóa với PostApprovalServiceImpl để không chỉnh sửa nội dung
     * cùng lúc một giao dịch khác đang duyệt bài.
     */

    private Post lockPost(UUID workspaceId, UUID id) {
        Post post = findPost(workspaceId, id);

        entityManager.lock(
                post,
                LockModeType.PESSIMISTIC_WRITE
        );

        return post;
    }


    private void validateSchedule(
            String status,
            OffsetDateTime scheduledAt
    ) {
        if (!List.of(
                "DRAFT",
                "READY",
                "SCHEDULED",
                "ARCHIVED"
        ).contains(status)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid post status"
            );
        }

        if ("SCHEDULED".equals(status)) {
            if (scheduledAt == null) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "Scheduled time is required"
                );
            }

            if (!scheduledAt.isAfter(OffsetDateTime.now())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "Scheduled time must be in the future"
                );
            }
        } else if (scheduledAt != null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Scheduled time is only allowed for SCHEDULED posts"
            );
        }
    }

    private void ensureNotQueued(Post post) {
        if (repo.hasPublishingTasks(post.id)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Post already has publishing tasks"
            );
        }
    }

    @Override
    @Transactional
    public PostDto.View create(
            UUID workspaceId,
            PostDto.Create r
    ) {
        access.admin(workspaceId);

        String status = r.status() == null
                ? "DRAFT"
                : r.status();

        validateSchedule(status, r.scheduledAt());
        if ("SCHEDULED".equals(status)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Create post as DRAFT and complete approval before scheduling"
            );
        }
        Post post = new Post();
        post.workspace = access.workspace(workspaceId);
        post.topic = r.topic();
        post.content = r.content();
        post.status = status;
        post.scheduledAt = r.scheduledAt();

        return view(repo.save(post));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostDto.View> list(UUID workspaceId) {
        access.workspace(workspaceId);

        return repo.findByWorkspaceId(workspaceId)
                .stream()
                .map(this::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostDto.View> search(
            UUID workspaceId,
            int page,
            int size,
            String status,
            String search
    ) {
        access.workspace(workspaceId);

        if (page < 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Page must be greater than or equal to 0"
            );
        }

        if (size < 1 || size > 100) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Size must be between 1 and 100"
            );
        }


        String normalizedStatus =
                status == null
                        ? ""
                        : status.trim().toUpperCase(
                        java.util.Locale.ROOT
                );

        String normalizedSearch =
                search == null
                        ? ""
                        : search.trim();

        if (!normalizedStatus.isEmpty()
                && !List.of(
                "DRAFT",
                "READY",
                "SCHEDULED",
                "ARCHIVED"
        ).contains(normalizedStatus)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid post status"
            );
        }


        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        return repo.searchPosts(
                workspaceId,
                normalizedStatus,
                normalizedSearch,
                pageable
        ).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public PostDto.View detail(
            UUID workspaceId,
            UUID id
    ) {
        access.workspace(workspaceId);

        return view(findPost(workspaceId, id));
    }

    @Override
    @Transactional
    public PostDto.View update(
            UUID workspaceId,
            UUID id,
            PostDto.Update r
    ) {
        access.admin(workspaceId);

        Post post = lockPost(workspaceId, id);

        ensureNotQueued(post);

        String status = r.status() == null
                ? post.status
                : r.status();

        validateSchedule(status, r.scheduledAt());

        boolean contentChanged =
                !Objects.equals(post.topic, r.topic())
                        || !Objects.equals(post.content, r.content());

        if ("SCHEDULED".equals(status)) {

            if (!"APPROVED".equals(post.getApprovalStatus())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "Post must be approved before scheduling"
                );
            }

            if (contentChanged) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "Cannot change content while scheduling"
                );
            }
        }

        // Chỉ thay đổi nội dung / chủ đề mới thu hồi phê duyệt.
        // Giữ lịch sử trong post_approvals để phục vụ kiểm toán.
        if (contentChanged) {
            post.setApprovalStatus("NOT_SUBMITTED");
            post.setApprovedBy(null);
            post.setApprovedAt(null);
        }

        post.topic = r.topic();
        post.content = r.content();
        post.status = status;
        post.scheduledAt = r.scheduledAt();

        return view(repo.save(post));
    }

    @Override
    @Transactional
    public void delete(
            UUID workspaceId,
            UUID id
    ) {
        access.admin(workspaceId);

        Post post = lockPost(workspaceId, id);

        ensureNotQueued(post);

        repo.delete(post);
    }
}
