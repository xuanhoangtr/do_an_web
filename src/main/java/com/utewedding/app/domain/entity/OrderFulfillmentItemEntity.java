package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.ShopDefaults;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import java.util.UUID;

@Entity
@Table(name = "order_fulfillment_items", indexes = {
        @Index(name = "idx_fulfillment_items_fulfillment", columnList = "fulfillment_id")
})
public class OrderFulfillmentItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId = ShopDefaults.PLATFORM_SHOP_ID;

    @Column(name = "fulfillment_id", nullable = false)
    private UUID fulfillmentId;

    @Column(name = "order_item_id", nullable = false, unique = true)
    private UUID orderItemId;

    @PrePersist void onCreate() { if (shopId == null) shopId = ShopDefaults.PLATFORM_SHOP_ID; }
    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public UUID getFulfillmentId() { return fulfillmentId; }
    public void setFulfillmentId(UUID value) { fulfillmentId = value; }
    public UUID getOrderItemId() { return orderItemId; }
    public void setOrderItemId(UUID value) { orderItemId = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OrderFulfillmentItemEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return EntityIdentity.hashCode(this); }
}
