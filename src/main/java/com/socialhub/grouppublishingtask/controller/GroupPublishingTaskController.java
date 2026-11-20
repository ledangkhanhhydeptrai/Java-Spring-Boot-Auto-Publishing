package com.socialhub.grouppublishingtask.controller;

import java.util.*;
import java.time.*;

import lombok.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
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
        Post p = posts.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Post not found"));
        access.workspace(p.workspace.id);
        return p;
    }

    private View view(GroupPublishingTask t) {
        return new View(t.id, t.facebookGroup.id, t.status, t.scheduledAt, t.publishedAt, t.publishedUrl);
    }

    @GetMapping
    public List<View> list(@PathVariable UUID postId) {
        post(postId);
        return tasks.findByPostId(postId).stream().map(this::view).toList();
    }

    @PostMapping
    public View create(@PathVariable UUID postId, @Valid @RequestBody Create r) {
        Post p = post(postId);
        access.admin(p.workspace.id);
        FacebookGroup g = groups.findById(r.facebookGroupId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Group not found"));
        if (!p.workspace.id.equals(g.workspace.id))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Different workspaces");
        GroupPublishingTask t = new GroupPublishingTask();
        t.post = p;
        t.facebookGroup = g;
        t.scheduledAt = r.scheduledAt();
        return view(tasks.save(t));
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

    private View status(UUID postId, UUID id, String status, String url) {
        Post p = post(postId);
        access.admin(p.workspace.id);
        GroupPublishingTask t = tasks.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Task not found"));
        if (!t.post.id.equals(postId)) throw new ApiException(HttpStatus.NOT_FOUND, "Task not found");
        if ("PUBLISHED".equals(t.status) || "CANCELLED".equals(t.status))
            throw new ApiException(HttpStatus.CONFLICT, "Task already finalized");
        t.status = status;
        if (url != null) {
            t.publishedUrl = url;
            t.publishedAt = OffsetDateTime.now();
        }
        return view(tasks.save(t));
    }
}
