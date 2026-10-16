package com.utewedding.app.dto.response;

import com.utewedding.app.domain.DeliveryAttemptOutcome;
import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptResponse(
        UUID id,
        UUID shipperId,
        DeliveryAttemptOutcome outcome,
        String reasonCode,
        String note,
        Instant attemptedAt,
        Instant nextAttemptAt,
        String proofUrl) {
}
