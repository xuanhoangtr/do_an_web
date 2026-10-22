package com.utewedding.app.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;

public record ReturnReviewRequest(@NotNull Boolean approve, boolean returnRequired,
                                  @Valid List<Item> items, String reason) {
    public record Item(@NotNull UUID orderItemId, @PositiveOrZero int approvedQuantity) {}
}