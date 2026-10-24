package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.PrivacyRequestStatus;
import com.utewedding.app.domain.PrivacyRequestType;
import com.utewedding.app.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "privacy_requests")
public class PrivacyRequestEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(name = "request_type", nullable = false, length = 20)
    private PrivacyRequestType requestType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private PrivacyRequestStatus status = PrivacyRequestStatus.PENDING;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "request_reason", length = 4096) private String requestReason;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "decision_reason", length = 4096) private String decisionReason;
    @Column(name = "processed_by") private UUID processedBy;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "processed_at") private Instant processedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;

    @PrePersist void onCreate() {
        Instant now = Instant.now();
        if (requestedAt == null) requestedAt = now;
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    public PrivacyRequestType getRequestType() { return requestType; }
    public void setRequestType(PrivacyRequestType value) { requestType = value; }
    public PrivacyRequestStatus getStatus() { return status; }
    public void setStatus(PrivacyRequestStatus value) { status = value; }
    public String getRequestReason() { return requestReason; }
    public void setRequestReason(String value) { requestReason = value; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String value) { decisionReason = value; }
    public UUID getProcessedBy() { return processedBy; }
    public void setProcessedBy(UUID value) { processedBy = value; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant value) { processedAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
