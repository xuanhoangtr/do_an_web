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
@Table(name = "password_reset_tokens", indexes = {
        @Index(name = "idx_password_reset_tokens_user_used", columnList = "user_id, used_at"),
        @Index(name = "idx_password_reset_tokens_expires_at", columnList = "expires_at")
})
public class PasswordResetTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "otp_hash", length = 64)
    private String otpHash;

    @Column(name = "otp_expires_at")
    private Instant otpExpiresAt;

    @Column(name = "otp_attempts", nullable = false)
    private int otpAttempts;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "request_ip", length = 64)
    private String requestIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PasswordResetTokenEntity() {
    }

    public PasswordResetTokenEntity(UUID userId, String tokenHash, Instant expiresAt, String requestIp) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.requestIp = requestIp;
        this.createdAt = Instant.now();
    }

    public void setOtp(String otpHash, Instant otpExpiresAt) {
        this.otpHash = otpHash;
        this.otpExpiresAt = otpExpiresAt;
        this.otpAttempts = 0;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getOtpHash() { return otpHash; }
    public Instant getOtpExpiresAt() { return otpExpiresAt; }
    public int getOtpAttempts() { return otpAttempts; }
    public void incrementOtpAttempts() { otpAttempts++; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
    public String getRequestIp() { return requestIp; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PasswordResetTokenEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
