package com.utewedding.app.dto.request;

import jakarta.validation.constraints.Size;
import java.util.UUID;

public record UpdateWeddingRequest(
        @Size(max = 160) String internalTitle,
        UUID templateId) {
}
