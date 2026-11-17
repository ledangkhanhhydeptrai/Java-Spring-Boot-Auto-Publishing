
package com.socialhub.postmedia.controller;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;

import com.socialhub.post.entity.Post;
import com.socialhub.post.repository.PostRepository;

import com.socialhub.postmedia.entity.PostMedia;
import com.socialhub.postmedia.repository.PostMediaRepository;

@RestController
@RequestMapping("/api/posts/{postId}/media")
@RequiredArgsConstructor
public class PostMediaController {

    private final PostMediaRepository repo;
    private final PostRepository posts;
    private final AccessService access;
    private final EntityManager entityManager;

    public record Create(
            @NotBlank String sourceUrl,
            String publicUrl,
            String mediaType,
            Integer displayOrder
    ) {
    }

    public record View(
            UUID id,
            String sourceUrl,
            String publicUrl,
            String mediaType,
            Integer displayOrder
    ) {
    }

    private Post post(UUID id) {
        Post p = posts.findById(id)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                "Post not found"
                        )
                );

        access.workspace(p.getWorkspace().getId());

        return p;
    }

    private Post lockForMutation(UUID postId) {

        Post p = post(postId);

        access.admin(p.getWorkspace().getId());

        // Synchronize media changes with approval decisions.
        entityManager.refresh(
                p,
                LockModeType.PESSIMISTIC_WRITE
        );

        // Do not modify media after publishing tasks exist.
        if (posts.hasPublishingTasks(postId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Post already has publishing tasks. "
                            + "Media cannot be modified."
            );
        }

        if ("ARCHIVED".equals(p.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Archived post media cannot be modified"
            );
        }

        return p;
    }

    private void revokeApproval(Post p) {

        p.setApprovalStatus("NOT_SUBMITTED");
        p.setApprovedBy(null);
        p.setApprovedAt(null);

        posts.save(p);
    }

    private View view(PostMedia m) {
        return new View(
                m.id,
                m.sourceUrl,
                m.publicUrl,
                m.mediaType,
                m.displayOrder
        );
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<View> list(@PathVariable UUID postId) {

        post(postId);

        return repo.findByPostId(postId)
                .stream()
                .map(this::view)
                .toList();
    }

    @PostMapping
    @Transactional
    public View create(
            @PathVariable UUID postId,
            @Valid @RequestBody Create r
    ) {

        Post p = lockForMutation(postId);

        PostMedia m = new PostMedia();

        m.post = p;
        m.sourceUrl = r.sourceUrl();
        m.publicUrl = r.publicUrl();

        m.mediaType = r.mediaType() == null
                ? "IMAGE"
                : r.mediaType();

        m.displayOrder = r.displayOrder() == null
                ? 0
                : r.displayOrder();

        if (!List.of("IMAGE", "VIDEO").contains(m.mediaType)
                || m.displayOrder < 0) {

            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid media type/order"
            );
        }

        PostMedia saved = repo.save(m);

        // Any media change invalidates prior approval.
        revokeApproval(p);

        return view(saved);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(
            @PathVariable UUID postId,
            @PathVariable UUID id
    ) {

        Post p = lockForMutation(postId);

        PostMedia m = repo.findById(id)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                "Media not found"
                        )
                );

        if (!m.getPost().getId().equals(postId)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "Media not found"
            );
        }

        repo.delete(m);

        // Deleting an image also invalidates approval.
        revokeApproval(p);
    }
}
