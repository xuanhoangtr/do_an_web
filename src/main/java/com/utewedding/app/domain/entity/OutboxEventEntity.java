package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.OutboxEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import com.utewedding.app.security.EncryptedStringConverter;
import jakarta.persistence.Convert;

@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_claimable", columnList = "next_attempt_at, created_at, id"),
        @Index(name = "idx_outbox_operations", columnList = "event_type, status, created_at")
})
public class OutboxEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "event_type", nullable = false, length = 120) private String eventType;
    @Column(name = "schema_version", nullable = false) private int schemaVersion;
    @Column(name = "aggregate_type", nullable = false, length = 80) private String aggregateType;
    @Column(name = "aggregate_id") private UUID aggregateId;
    @Column(name = "deduplication_key", nullable = false, unique = true, length = 190) private String deduplicationKey;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "text") private String payload;
    @Column(name = "payload_hash", nullable = false, length = 64) private String payloadHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private OutboxEventStatus status = OutboxEventStatus.PENDING;
    @Column(name = "correlation_id", length = 120) private String correlationId;
    @Column(name = "request_id", length = 120) private String requestId;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "max_attempts", nullable = false) private int maxAttempts = 8;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "lease_owner", length = 160) private String leaseOwner;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "last_error", length = 1000) private String lastError;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "processed_at") private Instant processedAt;

    protected OutboxEventEntity() {}
    public OutboxEventEntity(String type, String aggregateType, UUID aggregateId, String key,
                             String payload, String payloadHash, String correlationId, String requestId) {
        this.eventType = type; this.schemaVersion = 1; this.aggregateType = aggregateType; this.aggregateId = aggregateId;
        this.deduplicationKey = key; this.payload = payload; this.payloadHash = payloadHash;
        this.correlationId = correlationId; this.requestId = requestId; this.nextAttemptAt = Instant.now();
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getDeduplicationKey() { return deduplicationKey; }
    public String getPayload() { return payload; }
    public String getPayloadHash() { return payloadHash; }
    public OutboxEventStatus getStatus() { return status; }
    public void setStatus(OutboxEventStatus value) { status = value; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setAttemptCount(int value) { attemptCount = value; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant value) { nextAttemptAt = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { leaseOwner = value; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { leaseUntil = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { lastError = value; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant value) { processedAt = value; }
}
