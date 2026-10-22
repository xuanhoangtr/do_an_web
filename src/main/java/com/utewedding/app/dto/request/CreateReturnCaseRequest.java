package com.utewedding.app.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;

public record CreateReturnCaseRequest(
        @NotNull UUID fulfillmentId,
        @NotBlank @jakarta.validation.constraints.Size(max=80) String reason,
        @NotNull @Valid List<Item> items) {
    public record Item(@NotNull UUID orderItemId, @Positive int quantity) {}
}