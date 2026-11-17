
package com.socialhub.media.service.implement;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudinaryStorageService {

    private final Cloudinary cloudinary;

    public String uploadImage(
            byte[] imageBytes,
            String publicId
    ) {
        log.info(
                "[CLOUDINARY] Starting upload | publicId={} | size={} bytes",
                publicId,
                imageBytes == null ? 0 : imageBytes.length
        );

        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException(
                    "Image cannot be empty"
            );
        }

        try {
            log.info("[CLOUDINARY] Calling Cloudinary API");

            Map<?, ?> result = cloudinary.uploader().upload(
                    imageBytes,
                    ObjectUtils.asMap(
                            "resource_type", "image",
                            "folder", "socialhub/posts",
                            "public_id", publicId,
                            "overwrite", false,
                            "unique_filename", false
                    )
            );

            log.info(
                    "[CLOUDINARY] Upload response received | publicId={}",
                    publicId
            );

            Object secureUrl = result.get("secure_url");

            if (!(secureUrl instanceof String url)
                    || url.isBlank()) {
                throw new IllegalStateException(
                        "Cloudinary did not return secure_url"
                );
            }

            log.info(
                    "[CLOUDINARY] Upload successful | publicId={}",
                    publicId
            );

            return url;

        } catch (IOException | RuntimeException e) {

            log.error(
                    "[CLOUDINARY] Upload FAILED | publicId={} | type={} | message={}",
                    publicId,
                    e.getClass().getName(),
                    e.getMessage(),
                    e
            );

            throw new IllegalStateException(
                    "Cloudinary image upload failed",
                    e
            );
        }
    }
}
