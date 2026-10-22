package com.utewedding.app.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;

public record ReturnReceiveRequest(@NotNull @Valid List<Item> items) {
    public record Item(@NotNull UUID orderItemId, @PositiveOrZero int receivedQuantity) {}
}