package com.utewedding.app.service.port;

import java.time.Instant;
import java.util.UUID;

public record SessionTokens(UUID sessionId, String refreshToken, Instant refreshTokenExpiresAt) {
}
