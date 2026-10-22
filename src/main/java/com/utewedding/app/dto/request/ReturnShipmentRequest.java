package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ReturnShipmentRequest(@NotBlank String carrier, @NotBlank String trackingNumber) {}