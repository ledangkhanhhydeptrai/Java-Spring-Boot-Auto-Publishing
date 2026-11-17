
package com.socialhub.media.controller;

import com.socialhub.media.service.implement.CloudinaryStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/media-test")
public class CloudinaryTestController {

    private final CloudinaryStorageService storage;

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Map<String, String> upload(
            @RequestPart("file") MultipartFile file
    ) throws IOException {

        log.info(
                "[MEDIA TEST] Request received | filename={} | size={} | contentType={}",
                file.getOriginalFilename(),
                file.getSize(),
                file.getContentType()
        );

        if (file.isEmpty()
                || file.getContentType() == null
                || !file.getContentType().startsWith("image/")) {

            log.warn("[MEDIA TEST] Invalid image file");

            throw new IllegalArgumentException(
                    "Only image files are accepted"
            );
        }

        if (file.getSize() > 10L * 1024 * 1024) {

            log.warn(
                    "[MEDIA TEST] File exceeds 10MB | size={}",
                    file.getSize()
            );

            throw new IllegalArgumentException(
                    "Image exceeds 10 MB"
            );
        }

        log.info("[MEDIA TEST] Calling CloudinaryStorageService");

        String url = storage.uploadImage(
                file.getBytes(),
                UUID.randomUUID().toString()
        );

        log.info("[MEDIA TEST] Upload completed successfully");

        return Map.of("publicUrl", url);
    }
}
