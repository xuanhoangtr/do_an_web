package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AddCartItemRequest(
        @NotNull UUID productId,
        @NotNull @Min(1) @Max(99) Integer quantity,
        @Size(max = 2000) String customizationText) {

    public AddCartItemRequest(UUID productId, Integer quantity) {
        this(productId, quantity, null);
    }
}
