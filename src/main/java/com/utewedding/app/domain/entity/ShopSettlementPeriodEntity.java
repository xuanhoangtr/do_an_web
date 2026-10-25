package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.ShopSettlementPeriodStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
@Table(name = "shop_settlement_periods", indexes = {
        @Index(name = "idx_shop_settlement_periods_status", columnList = "status,period_end_at,id"),
        @Index(name = "idx_shop_settlement_periods_shop", columnList = "shop_id,period_start_at")
}, uniqueConstraints = @UniqueConstraint(name = "uq_shop_settlement_period_range",
        columnNames = {"shop_id", "period_start_at", "period_end_at"}))
public class ShopSettlementPeriodEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId;

    @Column(name = "period_start_at", nullable = false, updatable = false)
    private Instant periodStartAt;

    @Column(name = "period_end_at", nullable = false, updatable = false)
    private Instant periodEndAt;

    @Column(nullable = false, length = 60, updatable = false)
    private String timezone = "Asia/Ho_Chi_Minh";

    @Column(nullable = false, length = 3, updatable = false)
    private String currency = "VND";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShopSettlementPeriodStatus status = ShopSettlementPeriodStatus.DRAFT;

    @Column(name = "credit_amount", nullable = false)
    private long creditAmount;

    @Column(name = "debit_amount", nullable = false)
    private long debitAmount;

    @Column(name = "recovery_debit_amount", nullable = false)
    private long recoveryDebitAmount;

    @Column(name = "net_amount", nullable = false)
    private long netAmount;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "processing_by")
    private UUID processingBy;

    @Column(name = "processing_at")
    private Instant processingAt;

    @Column(name = "paid_by")
    private UUID paidBy;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "last_recalculation_id")
    private UUID lastRecalculationId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopSettlementPeriodEntity() { }

    public ShopSettlementPeriodEntity(UUID shopId, Instant periodStartAt, Instant periodEndAt) {
        this.shopId = shopId;
        this.periodStartAt = periodStartAt;
        this.periodEndAt = periodEndAt;
    }

    public void replaceTotals(long credit, long debit, int count) {
        replaceTotals(credit, debit, 0, count);
    }

    public void replaceTotals(long credit, long debit, long recoveryDebit, int count) {
        creditAmount = credit;
        debitAmount = debit;
        recoveryDebitAmount = recoveryDebit;
        netAmount = Math.subtractExact(Math.subtractExact(credit, debit), recoveryDebit);
        entryCount = count;
    }

    public void approve(UUID actorId, Instant at) {
        status = ShopSettlementPeriodStatus.APPROVED;
        approvedBy = actorId;
        approvedAt = at;
    }

    public void beginProcessing(UUID actorId, Instant at) {
        status = ShopSettlementPeriodStatus.PROCESSING;
        processingBy = actorId;
        processingAt = at;
    }

    public void resetApprovalForRecalculation(UUID recalculationId) {
        status = ShopSettlementPeriodStatus.DRAFT;
        approvedBy = null;
        approvedAt = null;
        lastRecalculationId = recalculationId;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public Instant getPeriodStartAt() { return periodStartAt; }
    public Instant getPeriodEndAt() { return periodEndAt; }
    public String getTimezone() { return timezone; }
    public String getCurrency() { return currency; }
    public ShopSettlementPeriodStatus getStatus() { return status; }
    public long getCreditAmount() { return creditAmount; }
    public long getDebitAmount() { return debitAmount; }
    public long getRecoveryDebitAmount() { return recoveryDebitAmount; }
    public long getNetAmount() { return netAmount; }
    public int getEntryCount() { return entryCount; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public UUID getProcessingBy() { return processingBy; }
    public Instant getProcessingAt() { return processingAt; }
    public UUID getPaidBy() { return paidBy; }
    public Instant getPaidAt() { return paidAt; }
    public UUID getLastRecalculationId() { return lastRecalculationId; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
