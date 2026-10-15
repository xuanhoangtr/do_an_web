package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "checkout_payment_allocations", indexes = {
        @Index(name = "idx_checkout_payment_allocations_group", columnList = "group_id, created_at"),
        @Index(name = "idx_checkout_payment_allocations_capture", columnList = "capture_transaction_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_checkout_payment_allocations_capture_order",
                columnNames = {"capture_transaction_id", "order_id"}),
        @UniqueConstraint(name = "uq_checkout_payment_allocations_group_order",
                columnNames = {"group_id", "order_id"})
})
public class CheckoutPaymentAllocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "capture_transaction_id", nullable = false, updatable = false)
    private UUID captureTransactionId;
    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;
    @Column(nullable = false, updatable = false)
    private long amount;
    @Column(nullable = false, length = 3, updatable = false)
    private String currency = "VND";
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CheckoutPaymentAllocationEntity() {
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCaptureTransactionId() { return captureTransactionId; }
    public void setCaptureTransactionId(UUID value) { captureTransactionId = value; }
    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID value) { groupId = value; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public long getAmount() { return amount; }
    public void setAmount(long value) { amount = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CheckoutPaymentAllocationEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
