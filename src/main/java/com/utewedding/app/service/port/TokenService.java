package com.utewedding.app.service.port;

import com.utewedding.app.domain.UserRole;
import java.util.UUID;

public interface TokenService {
    String issueAccessToken(UUID userId, String email, UserRole role, UUID sessionId);
    default String issueAccessToken(UUID userId, String email, UserRole role, UUID sessionId,
                                    int passwordVersion) {
        return issueAccessToken(userId, email, role, sessionId);
    }
    long accessTokenTtlSeconds();
}
