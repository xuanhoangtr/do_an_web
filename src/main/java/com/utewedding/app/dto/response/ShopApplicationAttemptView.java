package com.utewedding.app.dto.response;

import com.utewedding.app.domain.ShopApplicationStatus;
import java.time.Instant;
import java.util.UUID;

/** Admin-only view of one immutable application attempt and its decrypted review snapshot. */
public record ShopApplicationAttemptView(
        UUID id,
        int attemptNumber,
        ShopApplicationStatus status,
        Instant submittedAt,
        Instant reviewedAt,
        String reviewReason,
        String reviewerName,
        ShopApplicationSnapshot profile) { }
