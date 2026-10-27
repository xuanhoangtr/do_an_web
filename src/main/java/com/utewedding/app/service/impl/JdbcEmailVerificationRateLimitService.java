package com.utewedding.app.service.impl;

import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.service.EmailVerificationRateLimitService;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL-backed fixed-window limiter. The unique key and upsert make the
 * counter shared by all application instances and atomic under concurrency.
 */
@Service
public class JdbcEmailVerificationRateLimitService implements EmailVerificationRateLimitService {
    private static final String SCOPE_EMAIL = "EMAIL";
    private static final String SCOPE_IP = "IP";

    private final JdbcTemplate jdbcTemplate;
    private final byte[] keySecret;
    private final int maxSends;
    private final Duration window;

    public JdbcEmailVerificationRateLimitService(
            JdbcTemplate jdbcTemplate,
            @Value("${security.email-verification.rate-limit-key:${security.audit.ip-salt:utewedding-local}}") String keySecret,
            @Value("${security.email-verification.max-sends-per-hour:5}") int maxSends,
            @Value("${security.email-verification.rate-limit-window:1h}") Duration window) {
        if (keySecret == null || keySecret.isBlank()) {
            throw new IllegalArgumentException("Email verification rate-limit key must not be blank");
        }
        if (maxSends < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Email verification rate-limit policy is invalid");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.keySecret = keySecret.getBytes(StandardCharsets.UTF_8);
        this.maxSends = maxSends;
        this.window = window;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void consume(String email, String ipAddress) {
        Instant now = Instant.now();
        consumeScope(SCOPE_EMAIL, email, now);
        if (ipAddress != null && !ipAddress.isBlank()) {
            consumeScope(SCOPE_IP, ipAddress.trim(), now);
        }
    }

    private void consumeScope(String scope, String rawKey, Instant now) {
        String sql = """
                INSERT INTO email_verification_rate_limits
                    (scope, key_hash, window_started_at, request_count, updated_at)
                VALUES (?, ?, ?, 1, ?)
                ON CONFLICT (scope, key_hash) DO UPDATE SET
                    window_started_at = CASE
                        WHEN email_verification_rate_limits.window_started_at <= ? THEN EXCLUDED.window_started_at
                        ELSE email_verification_rate_limits.window_started_at END,
                    request_count = CASE
                        WHEN email_verification_rate_limits.window_started_at <= ? THEN 1
                        ELSE email_verification_rate_limits.request_count + 1 END,
                    updated_at = EXCLUDED.updated_at
                WHERE email_verification_rate_limits.window_started_at <= ?
                   OR email_verification_rate_limits.request_count < ?
                RETURNING request_count
                """;
        Timestamp nowTimestamp = Timestamp.from(now);
        Timestamp windowCutoff = Timestamp.from(now.minus(window));
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class,
                    scope, digest(rawKey), nowTimestamp, nowTimestamp,
                    windowCutoff, windowCutoff, windowCutoff, maxSends);
            if (count == null || count > maxSends) {
                throw new BusinessConflictException("Đã vượt quá số lần gửi mã trong cửa sổ giới hạn.");
            }
        } catch (EmptyResultDataAccessException exception) {
            throw new BusinessConflictException("Đã vượt quá số lần gửi mã trong cửa sổ giới hạn.");
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
