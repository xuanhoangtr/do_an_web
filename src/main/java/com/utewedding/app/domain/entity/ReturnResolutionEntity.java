package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.ReturnResolutionStatus;
import com.utewedding.app.domain.ReturnResolutionType;
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

@Entity
@Table(name = "return_resolutions", indexes = {
        @Index(name = "idx_return_resolutions_case_status", columnList = "return_case_id, status")
})
public class ReturnResolutionEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "return_case_id", nullable = false) private UUID returnCaseId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReturnResolutionType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private ReturnResolutionStatus status = ReturnResolutionStatus.PENDING;
    @Column(nullable = false) private long amount;
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 190) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(name = "payment_transaction_id") private UUID paymentTransactionId;
    @Column(name = "manual_reference", length = 190, unique = true) private String manualReference;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "processed_at") private Instant processedAt;

    protected ReturnResolutionEntity() {}
    public ReturnResolutionEntity(UUID caseId, ReturnResolutionType type, String key, String hash, long amount) {
        this.returnCaseId = caseId; this.type = type; this.idempotencyKey = key; this.requestHash = hash; this.amount = amount; this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    @com.fasterxml.jackson.annotation.JsonIgnore public String getManualReference() { return manualReference; }
    public void setManualReference(String value) { manualReference=value; }
    public UUID getReturnCaseId() { return returnCaseId; }
    public ReturnResolutionType getType() { return type; }
    public ReturnResolutionStatus getStatus() { return status; }
    public void setStatus(ReturnResolutionStatus value) { status = value; }
    public long getAmount() { return amount; }
    @com.fasterxml.jackson.annotation.JsonIgnore public String getIdempotencyKey() { return idempotencyKey; }
    @com.fasterxml.jackson.annotation.JsonIgnore public String getRequestHash() { return requestHash; }
    public UUID getPaymentTransactionId() { return paymentTransactionId; }
    public void setPaymentTransactionId(UUID value) { paymentTransactionId = value; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant value) { processedAt = value; }
}
