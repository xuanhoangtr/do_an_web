package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(name = "attempt_id") private UUID attemptId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32) private PaymentStatus status;
    @Column(nullable = false) private long amount;
    @Column(nullable = false, length = 3) private String currency = "VND";
    @Column(nullable = false, length = 60) private String provider = "MANUAL";
    @Column(name = "failure_reason", length = 500) private String failureReason;
    @Column(name = "provider_transaction_id", unique = true, length = 190) private String providerTransactionId;
    @Column(name = "refunded_amount", nullable = false) private long refundedAmount;
    @Column(name = "collected_amount", nullable = false) private long collectedAmount;
    @Column(name = "final_due_amount") private Long finalDueAmount;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "processed_at") private Instant processedAt;

    @jakarta.persistence.PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public UUID getAttemptId() { return attemptId; }
    public void setAttemptId(UUID value) { attemptId = value; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus value) { status = value; }

    /** Enforces the capture/refund state machine. Same-state writes are ignored. */
    public void transitionTo(PaymentStatus target) {
        if (target == null) {
            throw new IllegalStateException("Payment status is required.");
        }
        if (status == target) {
            return;
        }
        if (status == null || !status.canTransitionTo(target)) {
            throw new IllegalStateException("Invalid payment transition: " + status + " -> " + target);
        }
        status = target;
    }

    /**
     * Provider captured after the local attempt was already failed or timed out.
     * The caller must refund or reconcile that capture; this is not a normal sale.
     */
    public void acceptLateCapture() {
        if (status != PaymentStatus.FAILED && status != PaymentStatus.PENDING && status != PaymentStatus.PROCESSING) {
            throw new IllegalStateException("Cannot accept a late capture from " + status);
        }
        status = PaymentStatus.SUCCEEDED;
    }
    public long getAmount() { return amount; }
    public void setAmount(long value) { amount = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String value) { failureReason = value; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public void setProviderTransactionId(String value) { providerTransactionId = value; }
    public Instant getCreatedAt() { return createdAt; }
    public long getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(long value) { refundedAmount = value; }
    public long getCollectedAmount() { return collectedAmount; }
    public void setCollectedAmount(long value) { collectedAmount = value; }
    public Long getFinalDueAmount() { return finalDueAmount; }
    public void setFinalDueAmount(Long value) { finalDueAmount = value; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant value) { processedAt = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PaymentEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
