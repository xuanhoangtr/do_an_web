package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ReturnDisputeDecisionRequest(boolean upheld, @NotBlank String decision) {}