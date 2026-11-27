package com.socialhub.media.service.implement;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;
import com.socialhub.common.ApiException;
import com.socialhub.excelimport.repository.ExcelImportRepository;
import com.socialhub.post.entity.Post;
import com.socialhub.post.repository.PostRepository;
import com.socialhub.postmedia.entity.PostMedia;
import com.socialhub.postmedia.repository.PostMediaRepository;
import com.socialhub.security.AccessService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Comparator;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportMediaBatchService {

    private final ExcelImportRepository imports;
    private final PostMediaRepository mediaRepository;
    private final PostRepository postRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final GoogleDriveImageService googleDrive;
    private final CloudinaryStorageService cloudinary;
    private final AccessService access;

    public record Item(
            UUID postId,
            UUID mediaId,
            String sourceUrl,
            String publicUrl,
            String status,
            @JsonSerialize(using = ViDisplayTextSerializer.class) String message
    ) {
    }

    public record BatchResult(
            UUID importId,
            int processedPosts,
            int totalMedia,
            int uploaded,
            int reused,
            int skipped,
            int failed,
            Integer nextAfterRowNumber,
            boolean hasMore,
            List<Item> media
    ) {
    }

    /**
     * A batch processes at most maxPosts draft posts following afterRowNumber.
     * Call again with nextAfterRowNumber to continue. No publishing jobs are created.
     */
    public BatchResult process(
            UUID workspaceId,
            UUID importId,
            int maxPosts,
            Integer afterRowNumber
    ) {
        return processInternal(workspaceId, importId, maxPosts, afterRowNumber, null);
    }

    /**
     * Prepare only the media belonging to one Excel source row.
     * This method never creates a publishing job or schedules the post.
     */
    public BatchResult processOne(UUID workspaceId, UUID importId, int sourceRowNumber) {
        if (sourceRowNumber < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sourceRowNumber must be positive");
        }
        return processInternal(workspaceId, importId, 1, null, sourceRowNumber);
    }

    private BatchResult processInternal(UUID workspaceId, UUID importId,
                                        int maxPosts, Integer afterRowNumber,
                                        Integer onlyRowNumber) {
        access.workspace(workspaceId);
        access.admin(workspaceId);

        if (maxPosts < 1 || maxPosts > 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "maxPosts must be between 1 and 20");
        }
        if (afterRowNumber != null && afterRowNumber < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "afterRowNumber cannot be negative");
        }

        imports.findByIdAndWorkspaceId(importId, workspaceId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "Excel import not found"));

        // This repository query only selects DRAFT media from the requested import/workspace.
        log.info("[MEDIA-BATCH] Loading media | importId={}", importId);

        List<PostMedia> allMedia;

        try {
            allMedia = mediaRepository.findDraftMediaByImport(
                    workspaceId, importId
            );

            log.info(
                    "[MEDIA-BATCH] Found {} media records",
                    allMedia.size()
            );

        } catch (Exception e) {
            log.error(
                    "[MEDIA-BATCH] Failed to load media | importId={}",
                    importId,
                    e
            );

            throw e;
        }

        // Sort by source Excel row; do NOT depend on repository return order.
        // This keeps nextAfterRowNumber pagination deterministic.
        Map<Integer, List<PostMedia>> byRow = new TreeMap<>();
        for (PostMedia media : allMedia) {
            Integer rowNumber = media.getPost().getSourceRowNumber();
            if (rowNumber == null
                    || (onlyRowNumber != null && !onlyRowNumber.equals(rowNumber))
                    || (afterRowNumber != null && rowNumber <= afterRowNumber)) {
                continue;
            }
            byRow.computeIfAbsent(rowNumber, k -> new ArrayList<>()).add(media);
        }

        for (List<PostMedia> rowMedia : byRow.values()) {
            rowMedia.sort(Comparator.comparing(PostMedia::getDisplayOrder,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(PostMedia::getId));
        }
        List<Integer> rowNumbers = new ArrayList<>(byRow.keySet());
        int count = Math.min(maxPosts, rowNumbers.size());
        boolean hasMore = rowNumbers.size() > count;
        Integer nextRow = count == 0 ? null : rowNumbers.get(count - 1);

        int uploaded = 0;
        int reused = 0;
        int skipped = 0;
        int failed = 0;
        List<Item> results = new ArrayList<>();
        Map<String, String> urlCache = new HashMap<>();

        for (int i = 0; i < count; i++) {
            for (PostMedia media : byRow.get(rowNumbers.get(i))) {
                UUID postId = media.getPost().getId();
                UUID mediaId = media.getId();
                String sourceUrl = media.getSourceUrl();

                if (media.getPublicUrl() != null && !media.getPublicUrl().isBlank()) {
                    skipped++;
                    results.add(new Item(postId, mediaId, sourceUrl,
                            media.getPublicUrl(), "SKIPPED", "Already processed"));
                    continue;
                }
                if (!"IMAGE".equals(media.getMediaType())) {
                    skipped++;
                    results.add(new Item(postId, mediaId, sourceUrl,
                            null, "SKIPPED", "Only IMAGE is supported"));
                    continue;
                }
                // Legacy Excel imports can mislabel VIDEO/REEL Cloudinary URLs as
                // IMAGE. Never send those URLs to the Google Drive image downloader:
                // keep the media unchanged and let the Threads preflight block it.
                if (sourceUrl != null && sourceUrl.matches(
                        "(?i)^https://res\\.cloudinary\\.com/[^/]+/video/upload/.*$")) {
                    skipped++;
                    results.add(new Item(postId, mediaId, sourceUrl,
                            null, "SKIPPED", "Cloudinary video requires VIDEO/REEL pipeline"));
                    continue;
                }

                try {
                    if (sourceUrl == null || sourceUrl.isBlank()) {
                        throw new IllegalArgumentException("Missing image source URL");
                    }
                    // Direct Cloudinary delivery URLs need no Google Drive download/re-upload.
                    // Other URLs still go through the existing Google Drive-only downloader.
                    String url;
                    String resultStatus;
                    if (CloudinaryImageUrl.isDirectImageUrl(sourceUrl)) {
                        url = sourceUrl.trim();
                        resultStatus = "REUSED";
                    } else {
                        url = urlCache.get(sourceUrl);
                        if (url == null) {
                            List<String> existing = mediaRepository.findUploadedUrlsBySourceUrl(
                                    sourceUrl, PageRequest.of(0, 1));
                            if (!existing.isEmpty()
                                    && CloudinaryImageUrl.isDirectImageUrl(existing.get(0))) {
                                url = existing.get(0);
                            }
                        }
                        if (url != null && !url.isBlank()) {
                            resultStatus = "REUSED";
                        } else {
                            byte[] bytes = googleDrive.downloadImage(sourceUrl);
                            url = cloudinary.uploadImage(bytes,
                                    "drive_" + sha256(sourceUrl));
                            if (!CloudinaryImageUrl.isDirectImageUrl(url)) {
                                throw new IllegalStateException("Cloudinary returned an invalid image URL");
                            }
                            resultStatus = "UPLOADED";
                        }
                    }

                    // Persist in its own short transaction with a Post lock.
                    // Network I/O above stays outside the database transaction.
                    savePublicUrlSafely(postId, mediaId, sourceUrl, url);
                    if ("UPLOADED".equals(resultStatus)) {
                        uploaded++;
                    } else {
                        reused++;
                    }
                    urlCache.put(sourceUrl, url);
                    results.add(new Item(postId, mediaId, sourceUrl,
                            url, resultStatus, "Image ready"));

                } catch (Exception ex) {
                    failed++;
                    String reason = ex.getMessage() == null
                            ? ex.getClass().getSimpleName() : ex.getMessage();
                    log.warn("[MEDIA-BATCH] failed import={} post={} media={} reason={}",
                            importId, postId, mediaId, reason);
                    results.add(new Item(postId, mediaId, sourceUrl,
                            null, "FAILED", reason));
                }
            }
        }

        return new BatchResult(importId, count, results.size(), uploaded,
                reused, skipped, failed, nextRow, hasMore, results);
    }

    /**
     * Persist a processed media URL without overwriting concurrent changes.
     * The Post lock matches the single-post MediaProcessingService behavior.
     */
    private void savePublicUrlSafely(UUID postId, UUID mediaId,
                                     String expectedSourceUrl, String newPublicUrl) {
        transactionTemplate.executeWithoutResult(status -> {
            Post post = entityManager.find(Post.class, postId,
                    LockModeType.PESSIMISTIC_WRITE);
            if (post == null) {
                throw new IllegalStateException("Post no longer exists");
            }
            if (!"DRAFT".equals(post.getStatus())) {
                throw new IllegalStateException("Only DRAFT posts can be processed in Excel batch");
            }
            if (postRepository.hasPublishingTasks(postId)) {
                throw new IllegalStateException("Post already has publishing tasks");
            }

            PostMedia current = mediaRepository.findById(mediaId)
                    .orElseThrow(() -> new IllegalStateException("Media no longer exists"));
            if (!postId.equals(current.getPost().getId())
                    || !Objects.equals(expectedSourceUrl, current.getSourceUrl())
                    || !"IMAGE".equals(current.getMediaType())) {
                throw new IllegalStateException("Media changed while processing");
            }
            if (newPublicUrl == null || newPublicUrl.isBlank()) {
                throw new IllegalStateException("Cloudinary URL is empty");
            }
            if (current.getPublicUrl() != null && !current.getPublicUrl().isBlank()) {
                if (Objects.equals(current.getPublicUrl(), newPublicUrl)) {
                    return;
                }
                throw new IllegalStateException("Media was already updated by another worker");
            }
            current.setPublicUrl(newPublicUrl);
            mediaRepository.saveAndFlush(current);

            // Media changed: invalidate any earlier approval, like single-post processing.
            post.setApprovalStatus("NOT_SUBMITTED");
            post.setApprovedBy(null);
            post.setApprovedAt(null);
            postRepository.save(post);
        });
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

