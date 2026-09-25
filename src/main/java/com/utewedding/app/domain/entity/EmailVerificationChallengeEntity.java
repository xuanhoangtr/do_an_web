package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verification_challenges", indexes = {
        @Index(name = "idx_email_verify_email_created", columnList = "email, created_at"),
        @Index(name = "idx_email_verify_link_hash", columnList = "link_token_hash"),
        @Index(name = "idx_email_verify_user_consumed", columnList = "user_id, consumed_at")
})
public class EmailVerificationChallengeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = 320) private String email;
    @Column(name = "otp_hash", nullable = false, length = 64) private String otpHash;
    @Column(name = "link_token_hash", nullable = false, unique = true, length = 64)
    private String linkTokenHash;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "last_sent_at", nullable = false) private Instant lastSentAt;
    @Column(name = "window_started_at", nullable = false) private Instant windowStartedAt;
    @Column(name = "sent_count", nullable = false) private int sentCount;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "request_ip", length = 64) private String requestIp;
    @Column(name = "consumed_at") private Instant consumedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected EmailVerificationChallengeEntity() {
    }

    public EmailVerificationChallengeEntity(UUID userId, String email, String otpHash,
                                            String linkTokenHash, Instant expiresAt,
                                            Instant now, String requestIp) {
        this.userId = userId;
        this.email = email;
        this.otpHash = otpHash;
        this.linkTokenHash = linkTokenHash;
        this.expiresAt = expiresAt;
        this.lastSentAt = now;
        this.windowStartedAt = now;
        this.sentCount = 1;
        this.attemptCount = 0;
        this.requestIp = requestIp;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getOtpHash() { return otpHash; }
    public String getLinkTokenHash() { return linkTokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastSentAt() { return lastSentAt; }
    public Instant getWindowStartedAt() { return windowStartedAt; }
    public int getSentCount() { return sentCount; }
    public int getAttemptCount() { return attemptCount; }
    public String getRequestIp() { return requestIp; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void rotate(String otpHash, String linkTokenHash, Instant expiresAt, Instant now,
                       String requestIp, boolean resetWindow) {
        this.otpHash = otpHash;
        this.linkTokenHash = linkTokenHash;
        this.expiresAt = expiresAt;
        this.lastSentAt = now;
        this.requestIp = requestIp;
        this.attemptCount = 0;
        if (resetWindow) {
            this.windowStartedAt = now;
            this.sentCount = 1;
        } else {
            this.sentCount++;
        }
    }

    public void incrementAttemptCount() { attemptCount++; }
    public void setConsumedAt(Instant value) { consumedAt = value; }
    public boolean isActive(Instant now) {
        return consumedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
