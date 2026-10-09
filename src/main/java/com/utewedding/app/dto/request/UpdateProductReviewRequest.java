package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateProductReviewRequest(
        @Min(1) @Max(5) Integer rating,
        @Size(min = 50, max = 2000) String content) {
}
