package com.utewedding.app.dto.response;

import com.utewedding.app.domain.CeremonyType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TimelineItemResponse(
        UUID id,
        CeremonyType type,
        String title,
        OffsetDateTime startDateTime,
        OffsetDateTime endDateTime,
        String timezone,
        String venueName,
        String venueAddress,
        String googleMapsUrl,
        String description,
        int displayOrder,
        boolean visible) {
}
