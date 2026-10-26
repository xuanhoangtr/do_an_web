package com.utewedding.app.service.impl;

import com.utewedding.app.config.CloudinaryConfig;
import com.utewedding.app.domain.ManagedMediaStatus;
import com.utewedding.app.domain.entity.ManagedMediaObjectEntity;
import com.utewedding.app.integration.CloudinaryService;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Streams authorized private media without returning a provider URL to the browser. */
@Component
public class CloudinaryPrivateMediaProxy {
    private static final String CLOUDINARY_HOST = "res.cloudinary.com";
    private static final Set<String> PRIVATE_PURPOSES = Set.of(
            "SHOP_APPLICATION_EVIDENCE", "SUPPORT_ATTACHMENT", "PAYOUT_PROOF");
    private final CloudinaryService cloudinary;
    private final CloudinaryConfig config;
    private final long maxBytes;

    public CloudinaryPrivateMediaProxy(CloudinaryService cloudinary, CloudinaryConfig config,
                                      @Value("${media.max-image-size:10485760}") long maxBytes) {
        this.cloudinary = cloudinary;
        this.config = config;
        this.maxBytes = maxBytes;
    }

    OpenedDownload open(ManagedMediaObjectEntity media) {
        if (media == null || !"CLOUDINARY".equals(media.getProvider())
                || !"image".equals(media.getResourceType())
                || !"authenticated".equals(media.getDeliveryType())
                || !PRIVATE_PURPOSES.contains(media.getPurpose())
                || !validOwnershipShape(media)
                || media.getStatus() != ManagedMediaStatus.READY
                || media.getDetectedMime() == null
                || media.getPublicId() == null || media.getPublicId().isBlank()
                || maxBytes < 1
                || media.getByteSize() != null && (media.getByteSize() < 1 || media.getByteSize() > maxBytes)
                || !(media.getDetectedMime().equals("image/jpeg") || media.getDetectedMime().equals("image/png"))) {
            throw unavailable();
        }

        String signedUrl = cloudinary.signedPrivateUrl(media.getPublicId(), media.getDetectedFormat());
        URI uri;
        try {
            uri = URI.create(signedUrl);
        } catch (IllegalArgumentException exception) {
            throw unavailable();
        }
        validateProviderUri(uri);

        HttpURLConnection connection = null;
        try {
            URLConnection rawConnection = uri.toURL().openConnection();
            if (!(rawConnection instanceof HttpURLConnection http)) throw unavailable();
            connection = http;
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
            connection.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
            connection.setRequestProperty("Accept", "image/jpeg,image/png");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw unavailable();
            long length = connection.getContentLengthLong();
            if (length > maxBytes) throw unavailable();
            String type = normalizedContentType(connection.getContentType());
            if (!media.getDetectedMime().equals(type)) throw unavailable();
            InputStream stream = connection.getInputStream();
            return new OpenedDownload(stream, type, length, maxBytes, connection);
        } catch (IOException | RuntimeException exception) {
            if (connection != null) connection.disconnect();
            if (exception instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw unavailable();
        }
    }

    private static boolean validOwnershipShape(ManagedMediaObjectEntity media) {
        if ("SHOP_APPLICATION_EVIDENCE".equals(media.getPurpose())) {
            return media.getShopId() != null && media.getApplicationId() != null;
        }
        return media.getShopId() == null && media.getApplicationId() == null;
    }

    private void validateProviderUri(URI uri) {
        String cloudName = config.getCloudName();
        String path = uri.getRawPath();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !CLOUDINARY_HOST.equalsIgnoreCase(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getRawQuery() != null || cloudName == null || cloudName.isBlank()
                || path == null || !path.startsWith("/" + cloudName + "/image/authenticated/")) {
            throw unavailable();
        }
    }

    private static String normalizedContentType(String contentType) {
        if (contentType == null) return "";
        int separator = contentType.indexOf(';');
        String value = separator < 0 ? contentType : contentType.substring(0, separator);
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Không thể tải tài liệu cửa hàng.");
    }

    public static final class OpenedDownload implements AutoCloseable {
        private final InputStream inputStream;
        private final String contentType;
        private final long contentLength;
        private final long maxBytes;
        private final HttpURLConnection connection;

        private OpenedDownload(InputStream inputStream, String contentType, long contentLength,
                               long maxBytes, HttpURLConnection connection) {
            this.inputStream = inputStream;
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.maxBytes = maxBytes;
            this.connection = connection;
        }

        public InputStream inputStream() { return inputStream; }
        public String contentType() { return contentType; }
        public long contentLength() { return contentLength; }
        public long maxBytes() { return maxBytes; }

        @Override
        public void close() throws IOException {
            try {
                inputStream.close();
            } finally {
                connection.disconnect();
            }
        }
    }
}
