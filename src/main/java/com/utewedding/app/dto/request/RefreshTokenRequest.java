package com.utewedding.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for rotating a refresh token. */
public record RefreshTokenRequest(
        @NotBlank @Size(max = 512) String refreshToken) {
}
