package com.utewedding.app.dto.request;

import com.utewedding.app.domain.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateWeddingRequest(
        @NotBlank @Size(max = 160) String internalTitle,
        EventType eventType,
        UUID templateId) {
}
