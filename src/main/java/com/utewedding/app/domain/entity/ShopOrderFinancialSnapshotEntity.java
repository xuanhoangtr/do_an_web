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
@Table(name = "shop_order_financial_snapshots", indexes = {
        @Index(name = "idx_shop_order_financial_snapshot_shop_order", columnList = "shop_id,order_id")
}, uniqueConstraints = @UniqueConstraint(name = "uq_shop_order_financial_snapshot_order", columnNames = "order_id"))
public class ShopOrderFinancialSnapshotEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "merchandise_gross", nullable = false)
    private long merchandiseGross;

    @Column(name = "shop_funded_discount", nullable = false)
    private long shopFundedDiscount;

    @Column(name = "commission_base", nullable = false)
    private long commissionBase;

    @Column(name = "commission_rate_bps", nullable = false)
    private int commissionRateBps;

    @Column(name = "commission_amount", nullable = false)
    private long commissionAmount;

    @Column(name = "vendor_payable", nullable = false)
    private long vendorPayable;

    @Column(name = "policy_version", nullable = false)
    private long policyVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShopOrderFinancialSnapshotEntity() { }

    public ShopOrderFinancialSnapshotEntity(UUID orderId, UUID shopId, long merchandiseGross,
                                            long shopFundedDiscount, long commissionBase,
                                            int commissionRateBps, long commissionAmount, long vendorPayable,
                                            long policyVersion, Instant createdAt) {
        this.orderId = orderId;
        this.shopId = shopId;
        this.merchandiseGross = merchandiseGross;
        this.shopFundedDiscount = shopFundedDiscount;
        this.commissionBase = commissionBase;
        this.commissionRateBps = commissionRateBps;
        this.commissionAmount = commissionAmount;
        this.vendorPayable = vendorPayable;
        this.policyVersion = policyVersion;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getShopId() { return shopId; }
    public long getMerchandiseGross() { return merchandiseGross; }
    public long getShopFundedDiscount() { return shopFundedDiscount; }
    public long getCommissionBase() { return commissionBase; }
    public int getCommissionRateBps() { return commissionRateBps; }
    public long getCommissionAmount() { return commissionAmount; }
    public long getVendorPayable() { return vendorPayable; }
    public long getPolicyVersion() { return policyVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
