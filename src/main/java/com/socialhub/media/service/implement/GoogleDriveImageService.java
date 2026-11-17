
package com.socialhub.media.service.implement;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class GoogleDriveImageService {

    private static final int MAX_IMAGE_BYTES =
            10 * 1024 * 1024;

    private static final Pattern FILE_ID_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_-]{10,200}$");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public byte[] downloadImage(String sourceUrl) {

        String fileId = extractFileId(sourceUrl);

        URI uri = URI.create(
                "https://drive.google.com/uc?export=download&id="
                        + fileId
        );

        // Google Drive có thể chuyển hướng sang máy chủ
        // lưu trữ nội dung của Google.
        // Không tự theo redirect tới host tùy ý.
        for (int redirect = 0; redirect < 5; redirect++) {

            validateDownloadUri(uri);

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "SocialHub/1.0")
                    .GET()
                    .build();

            try {
                HttpResponse<byte[]> response =
                        httpClient.send(
                                request,
                                new LimitedBodyHandler(
                                        MAX_IMAGE_BYTES
                                )
                        );

                int status = response.statusCode();

                if (status >= 300 && status < 400) {
                    String location = response.headers()
                            .firstValue("location")
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Google Drive redirect missing location"
                                    )
                            );

                    uri = uri.resolve(location);
                    continue;
                }

                if (status != 200) {
                    throw new IllegalStateException(
                            "Google Drive returned HTTP " + status
                    );
                }

                byte[] bytes = response.body();

                if (bytes.length == 0) {
                    throw new IllegalStateException(
                            "Google Drive returned empty file"
                    );
                }

                if (!isSupportedImage(bytes)) {
                    throw new IllegalStateException(
                            "Google Drive did not return a supported JPG, PNG or WEBP image"
                    );
                }

                return bytes;

            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot download Google Drive image",
                        e
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Google Drive download interrupted",
                        e
                );
            }
        }

        throw new IllegalStateException(
                "Too many Google Drive redirects"
        );
    }

    private String extractFileId(String sourceUrl) {

        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Google Drive URL is required"
            );
        }

        URI uri;

        try {
            uri = URI.create(sourceUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid Google Drive URL",
                    e
            );
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null
                || uri.getPort() != -1) {
            throw new IllegalArgumentException(
                    "Google Drive URL must use HTTPS"
            );
        }

        String host = uri.getHost();

        if (host == null || !(
                host.equalsIgnoreCase("drive.google.com")
                        || host.equalsIgnoreCase("www.drive.google.com")
        )) {
            throw new IllegalArgumentException(
                    "Only Google Drive sharing URLs are accepted"
            );
        }

        String path = uri.getPath();
        String fileId = null;

        if (path != null && path.startsWith("/file/d/")) {
            String[] parts = path.split("/");

            if (parts.length >= 4) {
                fileId = parts[3];
            }
        }

        if (fileId == null && (
                "/open".equals(path)
                        || "/uc".equals(path)
        )) {
            fileId = queryParameters(uri).get("id");
        }

        if (fileId == null
                || !FILE_ID_PATTERN.matcher(fileId).matches()) {
            throw new IllegalArgumentException(
                    "Cannot extract Google Drive file ID"
            );
        }

        return fileId;
    }

    private Map<String, String> queryParameters(URI uri) {

        String query = uri.getRawQuery();

        if (query == null || query.isBlank()) {
            return Map.of();
        }

        return Arrays.stream(query.split("&"))
                .map(part -> part.split("=", 2))
                .filter(parts -> parts.length == 2)
                .collect(Collectors.toMap(
                        parts -> URLDecoder.decode(
                                parts[0],
                                StandardCharsets.UTF_8
                        ),
                        parts -> URLDecoder.decode(
                                parts[1],
                                StandardCharsets.UTF_8
                        ),
                        (first, second) -> first
                ));
    }

    private void validateDownloadUri(URI uri) {

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null
                || uri.getPort() != -1) {
            throw new IllegalArgumentException(
                    "Unsafe download URL"
            );
        }

        String host = uri.getHost();

        if (host == null) {
            throw new IllegalArgumentException(
                    "Missing download host"
            );
        }

        String normalizedHost = host.toLowerCase(
                java.util.Locale.ROOT
        );

        boolean allowed =
                normalizedHost.equals("drive.google.com")
                        || normalizedHost.equals("www.drive.google.com")
                        || normalizedHost.equals("drive.usercontent.google.com")
                        || normalizedHost.equals("doc-0g-00-docs.googleusercontent.com")
                        || normalizedHost.endsWith(".googleusercontent.com");

        if (!allowed) {
            throw new IllegalArgumentException(
                    "Google Drive redirected to an unsupported host"
            );
        }
    }

    private boolean isSupportedImage(byte[] bytes) {

        // JPEG
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return true;
        }

        // PNG
        byte[] png = {
                (byte) 137, 80, 78, 71, 13, 10, 26, 10
        };

        if (bytes.length >= png.length) {
            boolean matches = true;

            for (int i = 0; i < png.length; i++) {
                if (bytes[i] != png[i]) {
                    matches = false;
                    break;
                }
            }

            if (matches) {
                return true;
            }
        }

        // WEBP
        return bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P';
    }

    private static class LimitedBodyHandler
            implements HttpResponse.BodyHandler<byte[]> {

        private final int maxBytes;

        LimitedBodyHandler(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public HttpResponse.BodySubscriber<byte[]> apply(
                HttpResponse.ResponseInfo responseInfo
        ) {
            return HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.ofByteArray(),
                    bytes -> {
                        if (bytes.length > maxBytes) {
                            throw new IllegalStateException(
                                    "Image exceeds 10 MB"
                            );
                        }

                        return bytes;
                    }
            );
        }
    }
}
