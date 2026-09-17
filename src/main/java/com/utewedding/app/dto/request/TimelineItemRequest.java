package com.utewedding.app.dto.request;

import com.utewedding.app.domain.CeremonyType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TimelineItemRequest(
        UUID id,
        @NotNull CeremonyType type,
        @NotBlank @Size(max = 160) String title,
        @NotNull OffsetDateTime startDateTime,
        OffsetDateTime endDateTime,
        @Size(max = 64) String timezone,
        @Size(max = 200) String venueName,
        @Size(max = 500) String venueAddress,
        @Size(max = 2048)
        @Pattern(regexp = "^https://(www\\.)?(google\\.[a-z.]+|goo\\.gl|maps\\.app\\.goo\\.gl)/.*$",
                message = "Chỉ chấp nhận liên kết Google Maps dùng HTTPS.")
        String googleMapsUrl,
        @Size(max = 2000) String description,
        @Min(0) @Max(10000) Integer displayOrder,
        Boolean visible) {
}
