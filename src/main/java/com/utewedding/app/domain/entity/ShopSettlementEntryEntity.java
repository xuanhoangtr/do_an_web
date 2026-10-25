package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shop_settlement_entries", indexes = {
        @Index(name = "idx_shop_settlement_entries_history", columnList = "shop_id,created_at,id"),
        @Index(name = "idx_shop_settlement_entries_order", columnList = "order_id,created_at,id")
}, uniqueConstraints = @UniqueConstraint(name = "uk_shop_settlement_entry_source", columnNames = "source_identity"))
public class ShopSettlementEntryEntity {
    public enum Type { SALE, REFUND, ADJUSTMENT, RECOVERY }
    public enum Direction { CREDIT, DEBIT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "order_id")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private Type entryType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Direction direction;

    @Column(nullable = false)
    private long amount;

    @Column(name = "source_identity", nullable = false, length = 190)
    private String sourceIdentity;

    @Column(name = "source_reference", nullable = false, length = 190)
    private String sourceReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShopSettlementEntryEntity() { }

    public ShopSettlementEntryEntity(UUID shopId, UUID orderId, Type entryType, Direction direction,
                                     long amount, String sourceIdentity, String sourceReference, Instant createdAt) {
        this.shopId = shopId;
        this.orderId = orderId;
        this.entryType = entryType;
        this.direction = direction;
        this.amount = amount;
        this.sourceIdentity = sourceIdentity;
        this.sourceReference = sourceReference;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public UUID getOrderId() { return orderId; }
    public Type getEntryType() { return entryType; }
    public Direction getDirection() { return direction; }
    public long getAmount() { return amount; }
    public String getSourceIdentity() { return sourceIdentity; }
    public String getSourceReference() { return sourceReference; }
    public Instant getCreatedAt() { return createdAt; }
}
