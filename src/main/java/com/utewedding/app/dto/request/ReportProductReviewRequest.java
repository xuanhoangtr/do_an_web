package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReportProductReviewRequest(@NotBlank @Size(max = 1000) String reason) {
}
