package com.socialhub.media.service.implement;

import java.net.URI;
import java.util.Locale;

/**
 * Accept only direct Cloudinary image delivery URLs as reusable media sources.
 * Does not fetch arbitrary user-controlled URLs.
 */
public final class CloudinaryImageUrl {
    private CloudinaryImageUrl() {}

    public static boolean isDirectImageUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || !"res.cloudinary.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return false;
            }
            String path = uri.getRawPath();
            if (path == null || path.isBlank() || path.contains("%") || path.contains("\\")) {
                return false;
            }
            // /<cloud_name>/image/upload/<public_id or transformations...>
            String[] parts = path.split("/", -1);
            if (parts.length < 5 || parts[1].isBlank()
                    || !"image".equals(parts[2])
                    || !"upload".equals(parts[3])) {
                return false;
            }
            for (String part : parts) {
                if (".".equals(part) || "..".equals(part)) return false;
            }
            String filename = parts[parts.length - 1].toLowerCase(Locale.ROOT);
            return !filename.isBlank() && !filename.equals(".") && !filename.equals("..");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
