package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Refresh-session persistence; only the SHA-256 token digest is stored. */
@Entity
@Table(name = "auth_sessions")
public class AuthSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "refresh_token_hash", nullable = false, unique = true, length = 64) private String refreshTokenHash;
    @Column(name = "family_id", nullable = false) private UUID familyId;
    @Column(nullable = false) private int generation;
    @Column(name = "user_agent", length = 512) private String userAgent;
    @Column(name = "ip_address", length = 64) private String ipAddress;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "revoked_reason", length = 120) private String revokedReason;
    @Column(name = "rotated_at") private Instant rotatedAt;
    @Column(name = "last_used_at") private Instant lastUsedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getRefreshTokenHash() { return refreshTokenHash; }
    public UUID getFamilyId() { return familyId; }
    public int getGeneration() { return generation; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getRotatedAt() { return rotatedAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setId(UUID value) { id = value; }
    public String getUserAgent() { return userAgent; }
    public String getIpAddress() { return ipAddress; }
    public String getRevokedReason() { return revokedReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUserId(UUID value) { userId = value; }
    public void setRefreshTokenHash(String value) { refreshTokenHash = value; }
    public void setFamilyId(UUID value) { familyId = value; }
    public void setUserAgent(String value) { userAgent = value; }
    public void setIpAddress(String value) { ipAddress = value; }
    public void setExpiresAt(Instant value) { expiresAt = value; }
    public void setGeneration(int value) { generation = value; }
    public void setRevokedAt(Instant value) { revokedAt = value; }
    public void setRevokedReason(String value) { revokedReason = value; }
    public void setRotatedAt(Instant value) { rotatedAt = value; }
    public void setLastUsedAt(Instant value) { lastUsedAt = value; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AuthSessionEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
