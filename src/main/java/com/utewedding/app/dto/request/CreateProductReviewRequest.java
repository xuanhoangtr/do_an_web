package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProductReviewRequest(
        @Min(1) @Max(5) int rating,
        @NotBlank @Size(min = 50, max = 2000) String content) {
}
