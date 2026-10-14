package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssignFulfillmentRequest(@NotNull UUID shipperId, String trackingNumber) { }
