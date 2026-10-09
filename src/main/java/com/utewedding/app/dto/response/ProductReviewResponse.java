package com.utewedding.app.dto.response;

import com.utewedding.app.domain.ProductReviewStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductReviewResponse(UUID id, UUID productId, UUID userId, UUID orderId,
                                    String reviewerName, int rating, String content,
                                    ProductReviewStatus status, Instant createdAt, Instant updatedAt,
                                    List<ProductReviewMediaResponse> media,
                                    String productName, String productSlug) {
    public ProductReviewResponse(UUID id, UUID productId, UUID userId, UUID orderId,
                                 String reviewerName, int rating, String content,
                                 ProductReviewStatus status, Instant createdAt, Instant updatedAt,
                                 List<ProductReviewMediaResponse> media) {
        this(id, productId, userId, orderId, reviewerName, rating, content, status, createdAt, updatedAt,
                media, "Sản phẩm", productId == null ? "" : productId.toString());
    }
}
