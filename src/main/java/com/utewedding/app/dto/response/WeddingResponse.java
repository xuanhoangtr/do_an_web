package com.utewedding.app.dto.response;

import com.utewedding.app.domain.EventType;
import com.utewedding.app.domain.WeddingStatus;
import java.time.Instant;
import java.util.UUID;

public record WeddingResponse(
        UUID id,
        String internalTitle,
        EventType eventType,
        WeddingStatus status,
        String slug,
        UUID templateId,
        String templateKey,
        int version,
        String brideName,
        String groomName,
        Instant firstCeremonyAt,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        String previewUrl,
        String publicUrl) {
}
