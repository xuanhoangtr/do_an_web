package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.entity.ShopSettlementEntryEntity.Direction;
import com.utewedding.app.domain.entity.ShopSettlementEntryEntity.Type;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** Immutable assignment snapshot; one ledger entry can belong to only one period. */
@Entity
@Table(name = "shop_settlement_period_entries", indexes = {
        @Index(name = "idx_shop_settlement_period_entries_period", columnList = "period_id,created_at,id")
}, uniqueConstraints = @UniqueConstraint(name = "uq_shop_settlement_period_entry_source",
        columnNames = "settlement_entry_id"))
public class ShopSettlementPeriodEntryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "period_id", nullable = false, updatable = false)
    private UUID periodId;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId;

    @Column(name = "settlement_entry_id", nullable = false, updatable = false)
    private UUID settlementEntryId;

    @Column(name = "entry_amount", nullable = false, updatable = false)
    private long entryAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_direction", nullable = false, length = 8, updatable = false)
    private Direction entryDirection;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20, updatable = false)
    private Type entryType;

    @Column(name = "source_identity", nullable = false, length = 190, updatable = false)
    private String sourceIdentity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShopSettlementPeriodEntryEntity() { }

    public ShopSettlementPeriodEntryEntity(UUID periodId, UUID shopId, UUID settlementEntryId,
                                          long entryAmount, Direction entryDirection, Type entryType,
                                          String sourceIdentity, Instant createdAt) {
        this.periodId = periodId;
        this.shopId = shopId;
        this.settlementEntryId = settlementEntryId;
        this.entryAmount = entryAmount;
        this.entryDirection = entryDirection;
        this.entryType = entryType;
        this.sourceIdentity = sourceIdentity;
        this.createdAt = createdAt;
    }

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getPeriodId() { return periodId; }
    public UUID getShopId() { return shopId; }
    public UUID getSettlementEntryId() { return settlementEntryId; }
    public long getEntryAmount() { return entryAmount; }
    public Direction getEntryDirection() { return entryDirection; }
    public Type getEntryType() { return entryType; }
    public String getSourceIdentity() { return sourceIdentity; }
    public Instant getCreatedAt() { return createdAt; }
}
