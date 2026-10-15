package com.utewedding.app.dto.response;

import com.utewedding.app.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        PaymentStatus status,
        String provider,
        String failureReason,
        Instant processedAt,
        Instant createdAt) {
}
