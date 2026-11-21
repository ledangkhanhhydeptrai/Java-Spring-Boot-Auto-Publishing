package com.socialhub.grouppublishingtask.controller;

import java.util.*;
import java.time.*;

import lombok.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import com.socialhub.grouppublishingtask.entity.GroupPublishingTask;
import com.socialhub.grouppublishingtask.repository.GroupPublishingTaskRepository;
import com.socialhub.facebookgroup.entity.FacebookGroup;
import com.socialhub.facebookgroup.repository.FacebookGroupRepository;
import com.socialhub.post.entity.Post;
import com.socialhub.post.repository.PostRepository;

@Slf4j
@RestController
@RequestMapping("/api/posts/{postId}/group-tasks")
@RequiredArgsConstructor
public class GroupPublishingTaskController {
    private final GroupPublishingTaskRepository tasks;
    private final FacebookGroupRepository groups;
    private final PostRepository posts;
    private final AccessService access;

    public record Create(@NotNull UUID facebookGroupId, OffsetDateTime scheduledAt) {
    }

    public record Confirm(@NotBlank String publishedUrl) {
    }

    public record View(UUID id, UUID facebookGroupId, String status, OffsetDateTime scheduledAt,
                       OffsetDateTime publishedAt, String publishedUrl) {
    }

    private Post post(UUID id) {
        Post p = posts.findById(id)
                .orElseThrow(() ->
                        new ApiException(HttpStatus.NOT_FOUND, "Post not found"));

        UUID workspaceId = p.getWorkspace().getId();

        if (workspaceId == null) {
            throw new IllegalStateException(
                    "Post workspace ID is missing: " + id
            );
        }

        access.workspace(workspaceId);
        return p;
    }


    private View view(GroupPublishingTask t) {
        UUID facebookGroupId = t.getFacebookGroup() == null
                ? null
                : t.getFacebookGroup().getId();

        log.info(
                "[GROUP TASK VIEW] taskId={} | facebookGroupId={} | status={}",
                t.getId(),
                facebookGroupId,
                t.getStatus()
        );

        return new View(
                t.getId(),
                facebookGroupId,
                t.getStatus(),
                t.getScheduledAt(),
                t.getPublishedAt(),
                t.getPublishedUrl()
        );
    }


    @GetMapping
    public List<View> list(@PathVariable UUID postId) {
        post(postId);
        return tasks.findByPostId(postId).stream().map(this::view).toList();
    }

    @PostMapping
    public View create(
            @PathVariable UUID postId,
            @Valid @RequestBody Create r
    ) {
        log.info("[GROUP TASK] START create | postId={} | groupId={} | scheduledAt={}",
                postId, r.facebookGroupId(), r.scheduledAt());

        try {
            log.debug("[GROUP TASK] STEP 1 - Finding Post");
            Post p = post(postId);

            log.debug("[GROUP TASK] STEP 2 - Checking admin permission");
            UUID workspaceId = p.getWorkspace().getId();
            access.admin(workspaceId);

            log.debug("[GROUP TASK] STEP 3 - Finding Facebook Group");
            FacebookGroup g = groups.findById(r.facebookGroupId())
                    .orElseThrow(() ->
                            new ApiException(HttpStatus.NOT_FOUND, "Group not found"));

            log.debug("[GROUP TASK] STEP 4 - Validating workspace");
            UUID groupWorkspaceId = g.getWorkspace().getId();

            if (!workspaceId.equals(groupWorkspaceId)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "Different workspaces"
                );
            }

            log.debug("[GROUP TASK] STEP 5 - Creating entity");
            GroupPublishingTask t = new GroupPublishingTask();
            t.setPost(p);
            t.setFacebookGroup(g);
            t.setScheduledAt(r.scheduledAt());
            t.setStatus("PENDING");

            log.debug("[GROUP TASK] STEP 6 - Saving to PostgreSQL");
            GroupPublishingTask saved = tasks.saveAndFlush(t);

            log.info("[GROUP TASK] SUCCESS | taskId={} | postId={} | groupId={}",
                    saved.getId(), postId, r.facebookGroupId());

            return view(saved);

        } catch (Exception ex) {
            log.error(
                    "[GROUP TASK] FAILED | postId={} | groupId={} | errorType={} | message={}",
                    postId,
                    r.facebookGroupId(),
                    ex.getClass().getName(),
                    ex.getMessage(),
                    ex
            );

            throw ex;
        }
    }

    @PostMapping("/{id}/ready")
    public View ready(@PathVariable UUID postId, @PathVariable UUID id) {
        return status(postId, id, "READY", null);
    }

    @PostMapping("/{id}/confirm")
    public View confirm(@PathVariable UUID postId, @PathVariable UUID id, @Valid @RequestBody Confirm r) {
        return status(postId, id, "PUBLISHED", r.publishedUrl());
    }

    @PostMapping("/{id}/cancel")
    public View cancel(@PathVariable UUID postId, @PathVariable UUID id) {
        return status(postId, id, "CANCELLED", null);
    }

    private View status(UUID postId, UUID id, String newStatus, String url) {

        log.info(
                "[GROUP TASK] START status | postId={} | taskId={} | newStatus={}",
                postId, id, newStatus
        );

        try {
            Post p = post(postId);

            UUID workspaceId = p.getWorkspace().getId();
            access.admin(workspaceId);

            GroupPublishingTask t = tasks.findById(id)
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND,
                            "Task not found"
                    ));

            UUID taskPostId = t.getPost().getId();

            if (!postId.equals(taskPostId)) {
                throw new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Task does not belong to this Post"
                );
            }

            if ("PUBLISHED".equals(t.getStatus())
                    || "CANCELLED".equals(t.getStatus())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "Task already finalized"
                );
            }

            t.setStatus(newStatus);

            if ("PUBLISHED".equals(newStatus)) {
                t.setPublishedUrl(url);
                t.setPublishedAt(OffsetDateTime.now());
            }

            GroupPublishingTask saved = tasks.saveAndFlush(t);

            log.info(
                    "[GROUP TASK] SUCCESS | taskId={} | status={}",
                    saved.getId(),
                    saved.getStatus()
            );

            return view(saved);

        } catch (Exception ex) {
            log.error(
                    "[GROUP TASK] FAILED | postId={} | taskId={} | status={} | error={}",
                    postId, id, newStatus, ex.getMessage(), ex
            );

            throw ex;
        }
    }
}
