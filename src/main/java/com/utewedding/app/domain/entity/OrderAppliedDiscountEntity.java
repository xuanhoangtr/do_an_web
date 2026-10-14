package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.VoucherFundingSource;
import com.utewedding.app.domain.VoucherScope;
import com.utewedding.app.domain.VoucherType;
import com.utewedding.app.domain.VoucherUsageSlot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable attribution snapshot for a voucher applied to a shop order. */
@Entity
@Table(name = "order_applied_discounts", indexes = {
        @Index(name = "idx_order_applied_discounts_shop_order", columnList = "shop_id, order_id")
})
public class OrderAppliedDiscountEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(name = "shop_id", nullable = false) private UUID shopId;
    @Column(name = "voucher_id") private UUID voucherId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private VoucherUsageSlot slot;
    @Column(nullable = false, length = 80) private String code;
    @Enumerated(EnumType.STRING) @Column(name = "voucher_type", nullable = false, length = 20)
    private VoucherType voucherType;
    @Enumerated(EnumType.STRING) @Column(name = "voucher_scope", nullable = false, length = 16)
    private VoucherScope voucherScope;
    @Enumerated(EnumType.STRING) @Column(name = "funding_source", nullable = false, length = 16)
    private VoucherFundingSource fundingSource;
    @Column(name = "snapshot_source", nullable = false, length = 20) private String snapshotSource;
    @Column(name = "discount_amount", nullable = false) private long discountAmount;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected OrderAppliedDiscountEntity() { }

    public static OrderAppliedDiscountEntity liveUsage(UUID orderId, UUID shopId, UUID voucherId,
                                                        VoucherUsageSlot slot, String code,
                                                        VoucherType type, VoucherScope scope,
                                                        VoucherFundingSource fundingSource,
                                                        long discountAmount) {
        OrderAppliedDiscountEntity value = new OrderAppliedDiscountEntity();
        value.orderId = orderId;
        value.shopId = shopId;
        value.voucherId = voucherId;
        value.slot = slot;
        value.code = code;
        value.voucherType = type;
        value.voucherScope = scope;
        value.fundingSource = fundingSource;
        value.snapshotSource = "LIVE_USAGE";
        value.discountAmount = discountAmount;
        value.createdAt = Instant.now();
        return value;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getShopId() { return shopId; }
    public UUID getVoucherId() { return voucherId; }
    public VoucherUsageSlot getSlot() { return slot; }
    public String getCode() { return code; }
    public VoucherType getVoucherType() { return voucherType; }
    public VoucherScope getVoucherScope() { return voucherScope; }
    public VoucherFundingSource getFundingSource() { return fundingSource; }
    public String getSnapshotSource() { return snapshotSource; }
    public long getDiscountAmount() { return discountAmount; }
    public Instant getCreatedAt() { return createdAt; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof OrderAppliedDiscountEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }
    @Override public int hashCode() { return EntityIdentity.hashCode(this); }
}
