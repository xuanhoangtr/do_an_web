package com.utewedding.app.service;

import com.utewedding.app.domain.ProductReviewStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminReviewRow(UUID id, UUID productId, String productName, UUID userId,
                             String reviewerName, int rating, String content,
                             ProductReviewStatus status, Instant createdAt) {
}
