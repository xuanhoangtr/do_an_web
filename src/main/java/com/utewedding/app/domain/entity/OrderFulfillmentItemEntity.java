package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "order_fulfillment_items", indexes = {
        @Index(name = "idx_fulfillment_items_fulfillment", columnList = "fulfillment_id")
})
public class OrderFulfillmentItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "fulfillment_id", nullable = false)
    private UUID fulfillmentId;

    @Column(name = "order_item_id", nullable = false, unique = true)
    private UUID orderItemId;

    public UUID getId() { return id; }
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
