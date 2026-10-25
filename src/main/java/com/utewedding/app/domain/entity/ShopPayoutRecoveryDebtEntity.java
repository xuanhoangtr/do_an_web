package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Links a refund debit to an already-processing/paid payout for future recovery offsets. */
@Entity
@Table(name = "shop_payout_recovery_debts", indexes = {
        @Index(name = "idx_shop_payout_recovery_open", columnList = "shop_id,outstanding_amount,created_at")
}, uniqueConstraints = @UniqueConstraint(name = "uq_shop_payout_recovery_refund_entry",
        columnNames = "refund_entry_id"))
public class ShopPayoutRecoveryDebtEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "shop_id", nullable = false, updatable = false) private UUID shopId;
    @Column(name = "payout_id", nullable = false, updatable = false) private UUID payoutId;
    @Column(name = "refund_entry_id", nullable = false, updatable = false) private UUID refundEntryId;
    @Column(nullable = false, updatable = false) private long amount;
    @Column(name = "outstanding_amount", nullable = false) private long outstandingAmount;
    @Version @Column(nullable = false) private long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ShopPayoutRecoveryDebtEntity() { }

    public ShopPayoutRecoveryDebtEntity(UUID shopId, UUID payoutId, UUID refundEntryId, long amount, Instant at) {
        this.shopId = shopId;
        this.payoutId = payoutId;
        this.refundEntryId = refundEntryId;
        this.amount = amount;
        this.outstandingAmount = amount;
        this.createdAt = at;
    }

    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public UUID getPayoutId() { return payoutId; }
    public UUID getRefundEntryId() { return refundEntryId; }
    public long getAmount() { return amount; }
    public long getOutstandingAmount() { return outstandingAmount; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
