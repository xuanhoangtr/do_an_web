package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ReturnDisputeRequest(@NotBlank String reason) {}