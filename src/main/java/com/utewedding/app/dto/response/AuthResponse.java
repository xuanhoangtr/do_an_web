package com.utewedding.app.dto.response;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record AuthResponse(
        String accessToken,
        long expiresIn,
        UserResponse user,
        @JsonIgnore String refreshToken,
        @JsonIgnore Instant refreshTokenExpiresAt) {
}
