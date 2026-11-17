
package com.socialhub.post.service.interfaces;

import com.socialhub.post.dto.PostDto;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public interface PostService {

    PostDto.View create(
            UUID workspaceId,
            PostDto.Create request
    );

    // Giữ lại để tương thích với code cũ.
    List<PostDto.View> list(UUID workspaceId);

    Page<PostDto.View> search(
            UUID workspaceId,
            int page,
            int size,
            String status,
            String search
    );

    PostDto.View detail(
            UUID workspaceId,
            UUID id
    );

    PostDto.View update(
            UUID workspaceId,
            UUID id,
            PostDto.Update request
    );

    void delete(
            UUID workspaceId,
            UUID id
    );
}
