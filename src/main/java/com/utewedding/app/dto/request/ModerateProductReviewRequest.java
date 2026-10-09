package com.utewedding.app.dto.request;

import com.utewedding.app.domain.ProductReviewStatus;
import jakarta.validation.constraints.NotNull;

public record ModerateProductReviewRequest(@NotNull ProductReviewStatus status) {
}
