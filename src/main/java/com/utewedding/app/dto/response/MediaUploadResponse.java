package com.utewedding.app.dto.response;

import com.utewedding.app.domain.MediaAssetKind;
import com.utewedding.app.domain.MediaAssetStatus;
import java.util.UUID;

/** Stable API response for a persisted media upload; it contains no JPA type. */
public record MediaUploadResponse(
        UUID id,
        UUID weddingId,
        MediaAssetKind kind,
        MediaAssetStatus status,
        String publicId,
        String secureUrl,
        String resourceType,
        String format,
        Integer width,
        Integer height,
        Long bytes) {
}
