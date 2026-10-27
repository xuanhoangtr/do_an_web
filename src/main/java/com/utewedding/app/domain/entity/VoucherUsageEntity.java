package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.VoucherUsageSlot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "voucher_usages", indexes = {
        @Index(name = "idx_voucher_usages_voucher_user", columnList = "voucher_id, user_id"),
        @Index(name = "idx_voucher_usages_order", columnList = "order_id")
})
public class VoucherUsageEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "voucher_id", nullable = false) private UUID voucherId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private VoucherUsageSlot slot = VoucherUsageSlot.MERCHANDISE;
    @Column(name = "discount_amount", nullable = false) private long discountAmount;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getVoucherId() { return voucherId; }
    public void setVoucherId(UUID value) { voucherId = value; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public VoucherUsageSlot getSlot() { return slot; }
    public void setSlot(VoucherUsageSlot value) { slot = value; }
    public long getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(long value) { discountAmount = value; }
    public Instant getCreatedAt() { return createdAt; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof VoucherUsageEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }
    @Override public int hashCode() { return EntityIdentity.hashCode(this); }
}
