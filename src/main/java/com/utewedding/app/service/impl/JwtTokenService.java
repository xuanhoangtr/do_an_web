package com.utewedding.app.service.impl;

import com.utewedding.app.domain.UserRole;
import com.utewedding.app.security.AppRole;
import com.utewedding.app.security.JwtTokenProvider;
import com.utewedding.app.service.port.TokenService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Adapter that makes the existing application service port issue the migrated JWT. */
@Service
public class JwtTokenService implements TokenService {

    private final JwtTokenProvider tokenProvider;

    public JwtTokenService(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public String issueAccessToken(UUID userId, String email, UserRole role, UUID sessionId) {
        return issueAccessToken(userId, email, role, sessionId, 0);
    }

    @Override
    public String issueAccessToken(UUID userId, String email, UserRole role, UUID sessionId,
                                   int passwordVersion) {
        return tokenProvider.createAccessToken(
                userId,
                email,
                AppRole.from(role.name()),
                sessionId == null ? UUID.randomUUID().toString() : sessionId.toString(),
                passwordVersion);
    }

    @Override
    public long accessTokenTtlSeconds() {
        return tokenProvider.getAccessTokenTtlSeconds();
    }
}
