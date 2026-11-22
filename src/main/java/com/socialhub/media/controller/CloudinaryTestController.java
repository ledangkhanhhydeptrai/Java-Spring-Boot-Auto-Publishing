package com.socialhub.media.controller;

import com.socialhub.media.service.implement.CloudinaryStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/media-test")
public class CloudinaryTestController {

    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    private static final long MAX_TEST_VIDEO_SIZE = 20L * 1024 * 1024;

    private final CloudinaryStorageService storage;

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Map<String, String> upload(
            @RequestPart("file") MultipartFile file
    ) throws IOException {

        if (file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "File cannot be empty"
            );
        }

        String contentType = file.getContentType();

        if (contentType == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Content type is required"
            );
        }

        String publicId = UUID.randomUUID().toString();
        String url;

        if (contentType.startsWith("image/")) {

            if (file.getSize() > MAX_IMAGE_SIZE) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "Image exceeds 10 MB"
                );
            }

            url = storage.uploadImage(
                    file.getBytes(),
                    publicId
            );

        } else if ("video/mp4".equalsIgnoreCase(contentType)) {

            if (file.getSize() > MAX_TEST_VIDEO_SIZE) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "Test video exceeds 20 MB"
                );
            }

            url = storage.uploadVideo(
                    file.getBytes(),
                    publicId
            );

        } else {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only images and MP4 videos are supported"
            );
        }

        log.info(
                "[MEDIA TEST] Uploaded successfully | type={} | size={}",
                contentType,
                file.getSize()
        );

        return Map.of("publicUrl", url);
    }
}