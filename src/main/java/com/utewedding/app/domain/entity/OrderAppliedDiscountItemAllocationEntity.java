package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable merchandise allocation for an applied discount snapshot. */
@Entity
@Table(name = "order_applied_discount_item_allocations")
public class OrderAppliedDiscountItemAllocationEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "order_applied_discount_id", nullable = false) private UUID orderAppliedDiscountId;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(name = "shop_id", nullable = false) private UUID shopId;
    @Column(name = "order_item_id", nullable = false) private UUID orderItemId;
    @Column(nullable = false) private long amount;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected OrderAppliedDiscountItemAllocationEntity() { }

    public static OrderAppliedDiscountItemAllocationEntity of(UUID appliedDiscountId, UUID orderId,
                                                               UUID shopId, UUID orderItemId, long amount) {
        OrderAppliedDiscountItemAllocationEntity value = new OrderAppliedDiscountItemAllocationEntity();
        value.orderAppliedDiscountId = appliedDiscountId;
        value.orderId = orderId;
        value.shopId = shopId;
        value.orderItemId = orderItemId;
        value.amount = amount;
        value.createdAt = Instant.now();
        return value;
    }

    public UUID getId() { return id; }
    public UUID getOrderAppliedDiscountId() { return orderAppliedDiscountId; }
    public UUID getOrderId() { return orderId; }
    public UUID getShopId() { return shopId; }
    public UUID getOrderItemId() { return orderItemId; }
    public long getAmount() { return amount; }
    public Instant getCreatedAt() { return createdAt; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof OrderAppliedDiscountItemAllocationEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }
    @Override public int hashCode() { return EntityIdentity.hashCode(this); }
}
