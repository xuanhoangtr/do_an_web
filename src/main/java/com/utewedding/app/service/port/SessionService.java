package com.utewedding.app.service.port;

import com.utewedding.app.service.RequestContext;
import java.util.UUID;

public interface SessionService {
    SessionTokens createSession(UUID userId, RequestContext context);
    SessionRotation rotateSession(String refreshToken, RequestContext context);
    void revokeRefreshToken(String refreshToken, String reason);
    void revokeAllForUser(UUID userId, String reason);
    void revokeAllExcept(UUID userId, UUID keepSessionId, String reason);
    boolean isAccessSessionActive(UUID sessionId, UUID userId);

    record SessionRotation(UUID userId, SessionTokens tokens) {
    }
}
