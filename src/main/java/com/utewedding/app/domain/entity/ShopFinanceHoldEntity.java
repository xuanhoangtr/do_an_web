package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.ShopFinanceHoldSourceType;
import com.utewedding.app.domain.ShopFinanceHoldStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable financial hold identity and amount with a one-way release transition. */
@Entity
@Table(name = "shop_finance_holds")
public class ShopFinanceHoldEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30, updatable = false)
    private ShopFinanceHoldSourceType sourceType;

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @Column(name = "reason_code", nullable = false, length = 40, updatable = false)
    private String reasonCode;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShopFinanceHoldStatus status = ShopFinanceHoldStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by")
    private UUID releasedBy;

    protected ShopFinanceHoldEntity() { }

    public ShopFinanceHoldEntity(UUID shopId, UUID orderId, ShopFinanceHoldSourceType sourceType,
                                 UUID sourceId, String reasonCode, long amount) {
        if (shopId == null || orderId == null || sourceType == null || sourceId == null
                || reasonCode == null || reasonCode.isBlank() || amount < 0) {
            throw new IllegalArgumentException("A finance hold requires immutable source and amount details.");
        }
        this.shopId = shopId;
        this.orderId = orderId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.reasonCode = reasonCode;
        this.amount = amount;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public void release(UUID actorId) {
        if (status == ShopFinanceHoldStatus.RELEASED) return;
        status = ShopFinanceHoldStatus.RELEASED;
        releasedAt = Instant.now();
        releasedBy = actorId;
    }

    public void reactivate() {
        if (status == ShopFinanceHoldStatus.ACTIVE) return;
        status = ShopFinanceHoldStatus.ACTIVE;
        releasedAt = null;
        releasedBy = null;
    }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public UUID getOrderId() { return orderId; }
    public ShopFinanceHoldSourceType getSourceType() { return sourceType; }
    public UUID getSourceId() { return sourceId; }
    public String getReasonCode() { return reasonCode; }
    public long getAmount() { return amount; }
    public ShopFinanceHoldStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReleasedAt() { return releasedAt; }
    public UUID getReleasedBy() { return releasedBy; }
}
