
package com.socialhub.postmedia.repository;

import com.socialhub.postmedia.entity.PostMedia;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PostMediaRepository
        extends JpaRepository<PostMedia, UUID> {

    List<PostMedia> findByPostId(UUID postId);

    List<PostMedia> findByPostIdOrderByDisplayOrderAscIdAsc(
            UUID postId
    );

    // Lấy toàn bộ ảnh của các bài DRAFT thuộc lần import.
    @Query("""
            SELECT m
            FROM PostMedia m
            JOIN FETCH m.post p
            WHERE p.excelImport.id = :importId
              AND p.workspace.id = :workspaceId
              AND p.status = 'DRAFT'
            ORDER BY p.sourceRowNumber ASC,
                     m.displayOrder ASC,
                     m.id ASC
            """)
    List<PostMedia> findDraftMediaByImport(
            @Param("workspaceId") UUID workspaceId,
            @Param("importId") UUID importId
    );

    // Tìm URL Cloudinary đã upload từ cùng source_url.
    @Query("""
            SELECT m.publicUrl
            FROM PostMedia m
            WHERE m.sourceUrl = :sourceUrl
              AND m.publicUrl IS NOT NULL
              AND m.publicUrl <> ''
            ORDER BY m.createdAt ASC, m.id ASC
            """)
    List<String> findUploadedUrlsBySourceUrl(
            @Param("sourceUrl") String sourceUrl,
            Pageable pageable
    );
}
