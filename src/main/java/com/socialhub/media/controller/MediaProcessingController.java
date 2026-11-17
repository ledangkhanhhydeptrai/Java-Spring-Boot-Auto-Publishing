
package com.socialhub.media.controller;

import com.socialhub.common.ApiException;
import com.socialhub.media.service.interfaces.MediaProcessingService;
import com.socialhub.post.entity.Post;
import com.socialhub.post.repository.PostRepository;
import com.socialhub.security.AccessService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/posts/{postId}/media")
@Tag(name = "Media Processing Controller")
public class MediaProcessingController {

    private final MediaProcessingService mediaProcessing;
    private final PostRepository postRepository;
    private final AccessService access;

    @PostMapping("/process")
    public MediaProcessingService.ProcessResult process(
            @PathVariable UUID postId
    ) {

        Post post = postRepository.findById(postId)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                "Post not found"
                        )
                );

        access.admin(post.getWorkspace().getId());

        return mediaProcessing.processPostMedia(postId);
    }
}
