package com.utewedding.app.dto.request;

import com.utewedding.app.domain.StockDisposition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;

public record ReturnInspectionRequest(@NotNull Boolean accept, String reason, @Valid List<Item> items) {
    public record Item(@NotNull UUID orderItemId, @PositiveOrZero int receivedQuantity,
                       @NotNull StockDisposition disposition) {}
}