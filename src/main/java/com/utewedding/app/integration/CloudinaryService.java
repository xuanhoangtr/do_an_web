package com.utewedding.app.integration;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.utewedding.app.config.CloudinaryConfig;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Uploads images directly from MultipartFile bytes to Cloudinary.
 * No local path, File, S3, or MinIO operation is used by this service.
 */
@Service
public class CloudinaryService {

    private final Cloudinary cloudinary;
    private final CloudinaryConfig config;
    private final ManagedMediaValidator validator;

    @Autowired
    public CloudinaryService(
            Cloudinary cloudinary,
            CloudinaryConfig config,
            ManagedMediaValidator validator) {
        this.cloudinary = cloudinary;
        this.config = config;
        this.validator = validator;
    }

    /** Compatibility constructor for direct unit-test and adapter callers. */
    public CloudinaryService(Cloudinary cloudinary, CloudinaryConfig config, long maxImageSize) {
        this(cloudinary, config, new ManagedMediaValidator(maxImageSize, 6000, 6000, 25_000_000, 2));
    }

    /**
     * Sends the upload to Cloudinary and returns the HTTPS URL that a media
     * entity should persist in its url/storage field.
     */
    public CloudinaryUploadResult uploadImage(MultipartFile file, String weddingId, String kind) {
        return uploadImage(file, weddingId, kind, false);
    }

    /** Authenticated delivery is required for evidence, support and finance proofs. */
    public CloudinaryUploadResult uploadImage(MultipartFile file, String owner, String kind, boolean privateDelivery) {
        return uploadImage(file, owner, kind, privateDelivery, false);
    }

    /** Wedding galleries accept WebP; other upload flows keep their JPEG/PNG policy. */
    public CloudinaryUploadResult uploadWeddingImage(MultipartFile file, String weddingId, String kind) {
        return uploadImage(file, weddingId, kind, false, true);
    }

    public CloudinaryUploadResult uploadProductImage(MultipartFile file, String ownerId) {
        validator.validateProductImage(file);
        return uploadImage(file, ownerId, "PRODUCT", false, true);
    }

    private CloudinaryUploadResult uploadImage(
            MultipartFile file, String owner, String kind, boolean privateDelivery, boolean allowWebp) {
        ManagedMediaMetadata validated = allowWebp ? validator.validateWeddingImage(file) : validator.validate(file);
        if (!config.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Cloudinary chưa được cấu hình. Cần CLOUDINARY_CLOUD_NAME, "
                            + "CLOUDINARY_API_KEY và CLOUDINARY_API_SECRET.");
        }

        String folder = "utewedding/" + safeSegment(owner, "public")
                + "/" + safeSegment(kind, "gallery");
        try {
            Map<?, ?> response = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", folder,
                            "resource_type", "image",
                            "type", privateDelivery ? "authenticated" : "upload",
                            "secure", true,
                            "use_filename", false,
                            "unique_filename", true,
                            "overwrite", false));

            String secureUrl = value(response, "secure_url");
            if (secureUrl == null || !secureUrl.startsWith("https://")) {
                throw new IllegalStateException("Cloudinary không trả về secure_url HTTPS.");
            }
            String resourceType = value(response, "resource_type");
            String format = value(response, "format");
            if (!"image".equalsIgnoreCase(resourceType)
                    || format == null || !("jpg".equalsIgnoreCase(format)
                    || "jpeg".equalsIgnoreCase(format) || "png".equalsIgnoreCase(format)
                    || allowWebp && "webp".equalsIgnoreCase(format))) {
                throw new IllegalStateException("Cloudinary trả về media format không hợp lệ.");
            }
            return new CloudinaryUploadResult(
                    value(response, "public_id"),
                    secureUrl,
                    resourceType,
                    format,
                    integerValue(response, "width"),
                    integerValue(response, "height"),
                    longValue(response, "bytes"));
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Không thể tải ảnh lên Cloudinary.", exception);
        }
    }

    /** Convenience method for the common entity assignment use case. */
    public String uploadImageAndGetUrl(MultipartFile file, String weddingId, String kind) {
        return uploadImage(file, weddingId, kind).secureUrl();
    }

    /** Best-effort rollback for an upload whose database transaction failed. */
    public void deleteImageQuietly(String publicId) {
        deleteImageQuietly(publicId, "image", "upload");
    }

    public void deleteImageQuietly(String publicId, String resourceType, String deliveryType) {
        deleteImage(publicId, resourceType, deliveryType);
    }

    public DeleteResult deleteImage(String publicId, String resourceType, String deliveryType) {
        if (publicId == null || publicId.isBlank()) return DeleteResult.NOT_FOUND;
        if (!config.isConfigured()) return DeleteResult.ERROR;
        try {
            Object response = cloudinary.uploader().destroy(publicId,
                    ObjectUtils.asMap("resource_type", resourceType, "type", deliveryType, "invalidate", true));
            if (response instanceof java.util.Map<?, ?> values) {
                Object result = values.get("result");
                if ("ok".equalsIgnoreCase(String.valueOf(result))) return DeleteResult.DELETED;
                if ("not found".equalsIgnoreCase(String.valueOf(result))) return DeleteResult.NOT_FOUND;
            }
            return DeleteResult.ERROR;
        } catch (Exception exception) {
            return DeleteResult.ERROR;
        }
    }

    public enum DeleteResult { DELETED, NOT_FOUND, ERROR }

    public void validateImage(MultipartFile file) {
        validator.validate(file);
    }

    public String signedPrivateUrl(String publicId, String format) {
        if (!config.isConfigured() || publicId == null || publicId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Private media is unavailable.");
        }
        String suffix = format == null || format.isBlank() ? publicId : publicId + "." + format;
        String generated = cloudinary.url().resourceType("image").type("authenticated").signed(true).generate(suffix);
        // The Cloudinary SDK appends its analytics query parameter (currently _a=...).
        // Private media is streamed through our proxy, which deliberately rejects all
        // provider URL query strings; the analytics value is not part of the signature
        // and is safe to omit from this server-side fetch.
        int queryStart = generated.indexOf('?');
        return queryStart < 0 ? generated : generated.substring(0, queryStart);
    }

    private static String safeSegment(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized.substring(0, Math.min(normalized.length(), 80));
    }

    private static String value(Map<?, ?> response, String key) {
        Object value = response.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer integerValue(Map<?, ?> response, String key) {
        String value = value(response, key);
        return value == null ? null : Integer.valueOf(value);
    }

    private static Long longValue(Map<?, ?> response, String key) {
        String value = value(response, key);
        return value == null ? null : Long.valueOf(value);
    }
}
