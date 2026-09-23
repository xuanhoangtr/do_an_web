package com.utewedding.app.dto.response;

import com.utewedding.app.domain.UserRole;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String displayName,
        UserRole role,
        boolean emailVerified,
        Instant createdAt) {
}
