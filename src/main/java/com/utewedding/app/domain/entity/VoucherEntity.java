package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.VoucherType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vouchers")
public class VoucherEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, unique = true, length = 80) private String code;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private VoucherType type;
    @Column(name = "value_amount", nullable = false) private long valueAmount;
    @Column(name = "minimum_order_amount", nullable = false) private long minimumOrderAmount;
    @Column(name = "maximum_discount_amount") private Long maximumDiscountAmount;
    @Column(name = "starts_at", nullable = false) private Instant startsAt;
    @Column(name = "ends_at", nullable = false) private Instant endsAt;
    @Column(name = "total_quota") private Integer totalQuota;
    @Column(name = "per_user_quota") private Integer perUserQuota;
    @Column(name = "used_count", nullable = false) private int usedCount;
    @Column(nullable = false) private boolean active = true;
    @Version @Column(nullable = false) private long version;

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String value) { code = value; }
    public VoucherType getType() { return type; }
    public void setType(VoucherType value) { type = value; }
    public long getValueAmount() { return valueAmount; }
    public void setValueAmount(long value) { valueAmount = value; }
    public long getMinimumOrderAmount() { return minimumOrderAmount; }
    public void setMinimumOrderAmount(long value) { minimumOrderAmount = value; }
    public Long getMaximumDiscountAmount() { return maximumDiscountAmount; }
    public void setMaximumDiscountAmount(Long value) { maximumDiscountAmount = value; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant value) { startsAt = value; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant value) { endsAt = value; }
    public Integer getTotalQuota() { return totalQuota; }
    public void setTotalQuota(Integer value) { totalQuota = value; }
    public Integer getPerUserQuota() { return perUserQuota; }
    public void setPerUserQuota(Integer value) { perUserQuota = value; }
    public int getUsedCount() { return usedCount; }
    public void setUsedCount(int value) { usedCount = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public long getVersion() { return version; }
    public void setVersion(long value) { version = value; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof VoucherEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }
    @Override public int hashCode() { return EntityIdentity.hashCode(this); }
}
