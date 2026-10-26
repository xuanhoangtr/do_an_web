package com.utewedding.app.integration;

/** Cloudinary identifiers returned to the media entity/controller after upload. */
public record CloudinaryUploadResult(
        String publicId,
        String secureUrl,
        String resourceType,
        String format,
        Integer width,
        Integer height,
        Long bytes) {
}
