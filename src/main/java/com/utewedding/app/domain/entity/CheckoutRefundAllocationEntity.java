package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable attribution of a group-owned provider refund to one child order's
 * original, verified checkout capture allocation.
 */
@Entity
@Table(name = "checkout_refund_allocations", indexes = {
        @Index(name = "idx_checkout_refund_allocations_capture",
                columnList = "capture_allocation_id, refund_transaction_id"),
        @Index(name = "idx_checkout_refund_allocations_order",
                columnList = "group_id, order_id, created_at")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_checkout_refund_allocations_group_transaction",
                columnNames = {"group_id", "refund_transaction_id"}),
        @UniqueConstraint(name = "uq_checkout_refund_allocations_capture_transaction",
                columnNames = {"capture_allocation_id", "refund_transaction_id"})
})
public class CheckoutRefundAllocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "refund_transaction_id", nullable = false, updatable = false)
    private UUID refundTransactionId;

    @Column(name = "capture_allocation_id", nullable = false, updatable = false)
    private UUID captureAllocationId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency = "VND";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CheckoutRefundAllocationEntity() {
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID value) { groupId = value; }
    public UUID getRefundTransactionId() { return refundTransactionId; }
    public void setRefundTransactionId(UUID value) { refundTransactionId = value; }
    public UUID getCaptureAllocationId() { return captureAllocationId; }
    public void setCaptureAllocationId(UUID value) { captureAllocationId = value; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public long getAmount() { return amount; }
    public void setAmount(long value) { amount = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CheckoutRefundAllocationEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
