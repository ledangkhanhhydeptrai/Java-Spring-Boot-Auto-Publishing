
package com.socialhub.media.service.implement;

import com.socialhub.media.service.interfaces.MediaProcessingService;
import com.socialhub.post.entity.Post;
import com.socialhub.post.repository.PostRepository;
import com.socialhub.postmedia.entity.PostMedia;
import com.socialhub.postmedia.repository.PostMediaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaProcessingServiceImpl implements MediaProcessingService {

    private final PostMediaRepository mediaRepository;
    private final PostRepository postRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final GoogleDriveImageService googleDrive;
    private final CloudinaryStorageService cloudinary;

    @Override
    public ProcessResult processPostMedia(UUID postId) {

        List<PostMedia> mediaList =
                mediaRepository.findByPostIdOrderByDisplayOrderAscIdAsc(postId);

        List<MediaResult> results = new ArrayList<>();

        int uploaded = 0;
        int skipped = 0;
        int failed = 0;

        for (PostMedia media : mediaList) {

            UUID mediaId = media.getId();
            String sourceUrl = media.getSourceUrl();

            // 1. Ảnh đã có Cloudinary URL.
            if (media.getPublicUrl() != null
                    && !media.getPublicUrl().isBlank()) {

                skipped++;

                results.add(new MediaResult(
                        mediaId,
                        sourceUrl,
                        media.getPublicUrl(),
                        "SKIPPED",
                        "Media already uploaded"
                ));

                continue;
            }

            // 2. Chỉ xử lý IMAGE.
            if (!"IMAGE".equals(media.getMediaType())) {

                skipped++;

                results.add(new MediaResult(
                        mediaId,
                        sourceUrl,
                        null,
                        "SKIPPED",
                        "Only IMAGE media is supported"
                ));

                continue;
            }

            try {
                log.info(
                        "[MEDIA] Processing | postId={} | mediaId={}",
                        postId,
                        mediaId
                );

                // Khai báo bên ngoài if/else.
                String publicUrl;
                String processingStatus;
                String message;

                // 3. Cloudinary HTTPS image URLs can be reused directly.
                // Otherwise reuse a previously uploaded URL or process Google Drive source.
                if (CloudinaryImageUrl.isDirectImageUrl(sourceUrl)) {
                    publicUrl = sourceUrl.trim();
                    processingStatus = "REUSED";
                    message = "Reused direct Cloudinary image";
                } else {
                    List<String> existingUrls = mediaRepository.findUploadedUrlsBySourceUrl(
                            sourceUrl, PageRequest.of(0, 1));
                    if (!existingUrls.isEmpty()
                            && CloudinaryImageUrl.isDirectImageUrl(existingUrls.get(0))) {
                        publicUrl = existingUrls.get(0);
                        processingStatus = "REUSED";
                        message = "Reused existing Cloudinary image";
                    } else {
                        // Download remains limited to approved Google Drive hosts.
                        byte[] imageBytes = googleDrive.downloadImage(sourceUrl);
                        String publicId = "drive_" + sha256(sourceUrl);
                        publicUrl = cloudinary.uploadImage(imageBytes, publicId);
                        if (!CloudinaryImageUrl.isDirectImageUrl(publicUrl)) {
                            throw new IllegalStateException("Cloudinary returned an invalid image URL");
                        }
                        processingStatus = "UPLOADED";
                        message = "Image uploaded successfully";
                    }
                }

                // Existing Post lock, task guards, and approval invalidation remain unchanged.
                savePublicUrlSafely(postId, mediaId, sourceUrl, publicUrl);
                if ("UPLOADED".equals(processingStatus)) {
                    uploaded++;
                    log.info("[MEDIA] Uploaded | mediaId={} | publicUrl={}", mediaId, publicUrl);
                } else {
                    skipped++;
                    log.info("[MEDIA] Reused | mediaId={} | publicUrl={}", mediaId, publicUrl);
                }

                // 7. Trả kết quả đúng theo từng trường hợp.
                results.add(new MediaResult(
                        mediaId,
                        sourceUrl,
                        publicUrl,
                        processingStatus,
                        message
                ));

            } catch (Exception e) {

                failed++;

                log.error(
                        "[MEDIA] Failed | postId={} | mediaId={}",
                        postId,
                        mediaId,
                        e
                );

                results.add(new MediaResult(
                        mediaId,
                        sourceUrl,
                        null,
                        "FAILED",
                        "Image processing failed; check server logs"
                ));
            }
        }

        return new ProcessResult(
                postId,
                mediaList.size(),
                uploaded,
                skipped,
                failed,
                results
        );
    }

    /**
     * Keep network I/O outside the DB transaction. Lock the Post only while
     * persisting the Cloudinary URL, using the same Post lock as approval.
     * If media changes while being processed, do not overwrite the new data.
     */
    private void savePublicUrlSafely(
            UUID postId,
            UUID mediaId,
            String expectedSourceUrl,
            String newPublicUrl
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            Post post = entityManager.find(
                    Post.class, postId, LockModeType.PESSIMISTIC_WRITE
            );
            if (post == null) {
                throw new IllegalStateException("Post no longer exists");
            }

            if (postRepository.hasPublishingTasks(postId)) {
                throw new IllegalStateException(
                        "Post already has publishing tasks; media cannot be changed"
                );
            }

            PostMedia current = mediaRepository.findById(mediaId)
                    .orElseThrow(() -> new IllegalStateException("Media no longer exists"));
            if (!postId.equals(current.getPost().getId())
                    || !Objects.equals(expectedSourceUrl, current.getSourceUrl())
                    || !"IMAGE".equals(current.getMediaType())) {
                throw new IllegalStateException("Media changed while processing");
            }

            // Do not overwrite another worker's URL, even with the same value.
            if (current.getPublicUrl() != null && !current.getPublicUrl().isBlank()) {
                if (Objects.equals(current.getPublicUrl(), newPublicUrl)) {
                    return;
                }
                throw new IllegalStateException("Media URL was already updated");
            }

            if (newPublicUrl == null || newPublicUrl.isBlank()) {
                throw new IllegalStateException("Cloudinary returned an empty public URL");
            }

            current.setPublicUrl(newPublicUrl);
            mediaRepository.save(current);

            // Any actual media change invalidates an earlier approval.
            post.setApprovalStatus("NOT_SUBMITTED");
            post.setApprovedBy(null);
            post.setApprovedAt(null);
            postRepository.save(post);
        });
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    e
            );
        }
    }
}
