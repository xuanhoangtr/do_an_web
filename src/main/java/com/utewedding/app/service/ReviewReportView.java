package com.utewedding.app.service;

import com.utewedding.app.domain.ProductReviewReportStatus;
import java.time.Instant;
import java.util.UUID;

public record ReviewReportView(UUID id, UUID reviewId, ProductReviewReportStatus status,
                               String reviewContent, int rating, String productName,
                               String reviewerName, String reporterName, String reporterEmail,
                               String reason, Instant createdAt) {
}
