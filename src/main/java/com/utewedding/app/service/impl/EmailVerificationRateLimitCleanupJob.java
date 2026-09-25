package com.utewedding.app.service.impl;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keeps durable limiter state bounded without affecting active windows. */
@Component
public class EmailVerificationRateLimitCleanupJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailVerificationRateLimitCleanupJob.class);
    private final JdbcTemplate jdbcTemplate;
    private final Duration rateLimitWindow;

    public EmailVerificationRateLimitCleanupJob(
            JdbcTemplate jdbcTemplate,
            @Value("${security.email-verification.rate-limit-window:1h}") Duration rateLimitWindow) {
        if (rateLimitWindow.isNegative() || rateLimitWindow.isZero()) {
            throw new IllegalArgumentException("Email verification rate-limit window must be positive");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.rateLimitWindow = rateLimitWindow;
    }

    @Scheduled(cron = "${security.email-verification.rate-limit-cleanup-cron:0 17 * * * *}")
    @SchedulerLock(name = "email-verification-rate-limit-cleanup", lockAtMostFor = "15m")
    public void deleteExpiredWindows() {
        try {
            var cutoff = Timestamp.from(
                    Instant.now().minus(rateLimitWindow.multipliedBy(2)));
            jdbcTemplate.update("DELETE FROM email_verification_rate_limits "
                    + "WHERE window_started_at < ?", cutoff);
        } catch (RuntimeException exception) {
            LOGGER.error("Email verification rate-limit cleanup failed; it will retry on the next schedule", exception);
        }
    }
}
