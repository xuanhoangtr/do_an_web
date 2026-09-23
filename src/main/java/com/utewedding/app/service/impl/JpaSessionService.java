package com.utewedding.app.service.impl;

import com.utewedding.app.domain.entity.AuthSessionEntity;
import com.utewedding.app.repository.AuthSessionRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.SessionService;
import com.utewedding.app.service.port.SessionTokens;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.exception.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaSessionService implements SessionService {

    private final AuthSessionRepository sessionRepository;
    private final Duration refreshTokenTtl;
    private final AuditLogPort auditLog;
    private final SessionFamilyRevocationService familyRevocationService;
    private final SecureRandom secureRandom = new SecureRandom();

    public JpaSessionService(AuthSessionRepository sessionRepository,
                             @Value("${security.refresh-token.ttl:30d}") Duration refreshTokenTtl,
                             AuditLogPort auditLog,
                             SessionFamilyRevocationService familyRevocationService) {
        this.sessionRepository = sessionRepository;
        this.refreshTokenTtl = refreshTokenTtl;
        this.auditLog = auditLog;
        this.familyRevocationService = familyRevocationService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SessionTokens createSession(UUID userId, RequestContext context) {
        return createSession(userId, UUID.randomUUID(), 0, context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SessionService.SessionRotation rotateSession(String refreshToken, RequestContext context) {
        String hash = sha256(requireRefreshToken(refreshToken));
        AuthSessionEntity current = sessionRepository.findByRefreshTokenHashForUpdate(hash)
                .orElseThrow(() -> new UnauthorizedException("Refresh token không hợp lệ."));
        Instant now = Instant.now();
        if (current.getRevokedAt() != null || !current.getExpiresAt().isAfter(now)) {
            // A token that was rotated or explicitly revoked is a replay
            // signal. Revoke every session in the family before returning the
            // same generic error for both expired and reused tokens.
            if (current.getRevokedAt() != null) {
                familyRevocationService.revoke(current.getFamilyId(), "TOKEN_REUSE_DETECTED");
                auditLog.record("REFRESH_TOKEN_REUSE_DETECTED", current.getUserId(),
                        "AuthSession", current.getId(),
                        java.util.Map.of("familyId", current.getFamilyId()), context);
            }
            throw new UnauthorizedException("Refresh token đã hết hạn hoặc đã bị thu hồi.");
        }

        current.setRevokedAt(now);
        current.setRevokedReason("TOKEN_ROTATED");
        current.setRotatedAt(now);
        current.setLastUsedAt(now);
        sessionRepository.save(current);
        SessionTokens next = createSession(current.getUserId(), current.getFamilyId(),
                current.getGeneration() + 1, context);
        return new SessionService.SessionRotation(current.getUserId(), next);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeRefreshToken(String refreshToken, String reason) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        sessionRepository.findByRefreshTokenHash(sha256(refreshToken.trim())).ifPresent(session -> {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(Instant.now());
                session.setRevokedReason(truncate(reason, 120));
                sessionRepository.save(session);
            }
        });
    }

    private SessionTokens createSession(UUID userId, UUID familyId, int generation,
                                        RequestContext context) {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant expiresAt = Instant.now().plus(refreshTokenTtl);

        AuthSessionEntity session = new AuthSessionEntity();
        session.setUserId(userId);
        session.setRefreshTokenHash(sha256(refreshToken));
        session.setFamilyId(familyId);
        session.setGeneration(generation);
        session.setUserAgent(truncate(context == null ? null : context.userAgent(), 512));
        session.setIpAddress(truncate(context == null ? null : context.ipAddress(), 64));
        session.setExpiresAt(expiresAt);
        session = sessionRepository.save(session);
        return new SessionTokens(session.getId(), refreshToken, expiresAt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeAllForUser(UUID userId, String reason) {
        sessionRepository.revokeAllActive(userId, Instant.now(), truncate(reason, 120));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeAllExcept(UUID userId, UUID keepSessionId, String reason) {
        if (keepSessionId == null) {
            revokeAllForUser(userId, reason);
            return;
        }
        sessionRepository.revokeAllActiveExcept(userId, keepSessionId, Instant.now(), truncate(reason, 120));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAccessSessionActive(UUID sessionId, UUID userId) {
        return sessionId != null && userId != null
                && sessionRepository.existsByIdAndUserIdAndRevokedAtIsNullAndExpiresAtAfter(
                        sessionId, userId, Instant.now());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requireRefreshToken(String value) {
        if (value == null || value.isBlank()) {
            throw new UnauthorizedException("Refresh token không được để trống.");
        }
        return value.trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
