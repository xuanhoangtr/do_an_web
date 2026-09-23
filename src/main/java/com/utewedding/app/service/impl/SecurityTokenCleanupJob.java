package com.utewedding.app.service.impl;

import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes terminal authentication records while retaining a short forensic window. */
@Component
public class SecurityTokenCleanupJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityTokenCleanupJob.class);

    private static final String DELETE_SESSIONS = """
            DELETE FROM auth_sessions s
            USING (
                SELECT id
                FROM auth_sessions
                WHERE COALESCE(revoked_at, expires_at) < ?
                ORDER BY id
                LIMIT ?
            ) doomed
            WHERE s.id = doomed.id
            """;

    private static final String DELETE_PASSWORD_RESETS = """
            DELETE FROM password_reset_tokens t
            USING (
                SELECT id
                FROM password_reset_tokens
                WHERE COALESCE(
                    used_at,
                    GREATEST(expires_at, COALESCE(otp_expires_at, expires_at))
                ) < ?
                ORDER BY id
                LIMIT ?
            ) doomed
            WHERE t.id = doomed.id
            """;

    private static final String DELETE_EMAIL_CHALLENGES = """
            DELETE FROM email_verification_challenges c
            USING (
                SELECT id
                FROM email_verification_challenges
                WHERE COALESCE(consumed_at, expires_at) < ?
                ORDER BY id
                LIMIT ?
            ) doomed
            WHERE c.id = doomed.id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Duration authSessionRetention;
    private final Duration passwordResetRetention;
    private final Duration emailVerificationRetention;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public SecurityTokenCleanupJob(
            JdbcTemplate jdbcTemplate,
            @Value("${security.cleanup.auth-session-retention:30d}") Duration authSessionRetention,
            @Value("${security.cleanup.password-reset-retention:1d}") Duration passwordResetRetention,
            @Value("${security.cleanup.email-verification-retention:1d}") Duration emailVerificationRetention,
            @Value("${security.cleanup.batch-size:1000}") int batchSize,
            @Value("${security.cleanup.max-batches-per-run:100}") int maxBatchesPerRun) {
        this.jdbcTemplate = jdbcTemplate;
        this.authSessionRetention = positive(authSessionRetention, "auth session retention");
        this.passwordResetRetention = positive(passwordResetRetention, "password reset retention");
        this.emailVerificationRetention = positive(emailVerificationRetention,
                "email verification retention");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("security cleanup batch size must be positive");
        }
        if (maxBatchesPerRun <= 0) {
            throw new IllegalArgumentException("security cleanup max batches must be positive");
        }
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    @Scheduled(cron = "${security.cleanup.cron:0 0 3 * * *}")
    @SchedulerLock(name = "security-token-cleanup", lockAtMostFor = "30m")
    public void cleanup() {
        Instant now = Instant.now();
        try {
            int sessions = deleteBatches(DELETE_SESSIONS,
                    now.minus(authSessionRetention));
            int passwordResets = deleteBatches(DELETE_PASSWORD_RESETS,
                    now.minus(passwordResetRetention));
            int emailChallenges = deleteBatches(DELETE_EMAIL_CHALLENGES,
                    now.minus(emailVerificationRetention));
            LOGGER.info("Security cleanup removed {} sessions, {} password reset tokens and "
                            + "{} email verification challenges",
                    sessions, passwordResets, emailChallenges);
        } catch (RuntimeException exception) {
            LOGGER.error("Security token cleanup failed; it will retry on the next schedule", exception);
        }
    }

    private int deleteBatches(String sql, Instant cutoff) {
        int total = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            int deleted = jdbcTemplate.update(sql, cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
