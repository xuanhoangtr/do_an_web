package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.PlatformFundReceiptSourceKind;
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

@Entity
@Table(name = "platform_fund_receipt_allocations", indexes = {
        @Index(name = "idx_platform_fund_receipt_alloc_shop", columnList = "shop_id,receipt_id"),
        @Index(name = "idx_platform_fund_receipt_alloc_order", columnList = "order_id,receipt_id"),
        @Index(name = "idx_platform_fund_receipt_alloc_fulfillment", columnList = "fulfillment_id,receipt_id")
}, uniqueConstraints = @UniqueConstraint(name = "uq_platform_fund_receipt_allocation",
        columnNames = {"receipt_id", "allocation_key"}))
public class PlatformFundReceiptAllocationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "receipt_id", nullable = false)
    private UUID receiptId;

    @Column(name = "allocation_key", nullable = false, length = 190)
    private String allocationKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 32)
    private PlatformFundReceiptSourceKind sourceKind;

    @Column(name = "source_reference", nullable = false, length = 190)
    private String sourceReference;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "fulfillment_id")
    private UUID fulfillmentId;

    @Column(name = "gross_amount", nullable = false)
    private long grossAmount;

    @Column(name = "fee_amount", nullable = false)
    private long feeAmount;

    @Column(name = "deduction_amount", nullable = false)
    private long deductionAmount;

    @Column(name = "net_amount", nullable = false)
    private long netAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlatformFundReceiptAllocationEntity() { }

    public PlatformFundReceiptAllocationEntity(UUID receiptId, String allocationKey,
                                               PlatformFundReceiptSourceKind sourceKind,
                                               String sourceReference, UUID shopId, UUID orderId,
                                               UUID fulfillmentId, long grossAmount, long feeAmount,
                                               long deductionAmount, long netAmount) {
        this.receiptId = receiptId;
        this.allocationKey = allocationKey;
        this.sourceKind = sourceKind;
        this.sourceReference = sourceReference;
        this.shopId = shopId;
        this.orderId = orderId;
        this.fulfillmentId = fulfillmentId;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.deductionAmount = deductionAmount;
        this.netAmount = netAmount;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getReceiptId() { return receiptId; }
    public String getAllocationKey() { return allocationKey; }
    public PlatformFundReceiptSourceKind getSourceKind() { return sourceKind; }
    public String getSourceReference() { return sourceReference; }
    public UUID getShopId() { return shopId; }
    public UUID getOrderId() { return orderId; }
    public UUID getFulfillmentId() { return fulfillmentId; }
    public long getGrossAmount() { return grossAmount; }
    public long getFeeAmount() { return feeAmount; }
    public long getDeductionAmount() { return deductionAmount; }
    public long getNetAmount() { return netAmount; }
    public Instant getCreatedAt() { return createdAt; }
}
