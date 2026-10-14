package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record UpdateCartItemRequest(
        @NotNull UUID productId,
        @NotNull @Min(0) Integer quantity) {
}
