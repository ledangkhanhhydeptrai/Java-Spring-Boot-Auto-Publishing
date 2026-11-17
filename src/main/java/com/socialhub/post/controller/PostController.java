
package com.socialhub.post.controller;

import com.socialhub.post.dto.PostDto;
import com.socialhub.post.service.interfaces.PostService;
import com.socialhub.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService service;

    @PostMapping
    public ResponseEntity<ApiResponse<PostDto.View>> create(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody PostDto.Create request
    ) {
        PostDto.View result = service.create(workspaceId, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        HttpStatus.CREATED.value(),
                        "Create Post Successfully",
                        result
                ));
    }


    @GetMapping
    public ResponseEntity<ApiResponse<Page<PostDto.View>>> list(
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search
    ) {
        Page<PostDto.View> result = service.search(
                workspaceId,
                page,
                size,
                status,
                search
        );

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Get Posts Successfully",
                        result
                )
        );
    }


    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PostDto.View>> detail(
            @PathVariable UUID workspaceId,
            @PathVariable UUID id
    ) {
        PostDto.View result = service.detail(workspaceId, id);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Get Post Detail Successfully",
                        result
                )
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<PostDto.View>> update(
            @PathVariable UUID workspaceId,
            @PathVariable UUID id,
            @Valid @RequestBody PostDto.Update request
    ) {
        PostDto.View result = service.update(
                workspaceId,
                id,
                request
        );

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Update Post Successfully",
                        result
                )
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID workspaceId,
            @PathVariable UUID id
    ) {
        service.delete(workspaceId, id);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Delete Post Successfully",
                        null
                )
        );
    }
}
