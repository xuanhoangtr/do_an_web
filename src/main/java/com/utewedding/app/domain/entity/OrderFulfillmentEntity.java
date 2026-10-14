package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.FulfillmentStatus;
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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_fulfillments", indexes = {
        @Index(name = "idx_fulfillments_order", columnList = "order_id"),
        @Index(name = "idx_fulfillments_shipper_status", columnList = "shipper_id, status, created_at")
})
public class OrderFulfillmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FulfillmentStatus status = FulfillmentStatus.CONFIRMED;

    @Column(name = "shipper_id")
    private UUID shipperId;

    @Column(name = "carrier_id")
    private UUID carrierId;

    @Column(name = "tracking_number", length = 120)
    private String trackingNumber;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "delivery_proof_url", length = 2048)
    private String deliveryProofUrl;

    @Column(name = "delivery_proof_public_id", length = 512)
    private String deliveryProofPublicId;

    @Column(name = "collects_cod", nullable = false)
    private boolean collectsCod;

    @Column(name = "cod_amount", nullable = false)
    private long codAmount;

    @Column(name = "inventory_restored", nullable = false)
    private boolean inventoryRestored;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public FulfillmentStatus getStatus() { return status; }
    public void setStatus(FulfillmentStatus value) { status = value; }
    public UUID getShipperId() { return shipperId; }
    public void setShipperId(UUID value) { shipperId = value; }
    public UUID getCarrierId() { return carrierId; }
    public void setCarrierId(UUID value) { carrierId = value; }
    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String value) { trackingNumber = value; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant value) { deliveredAt = value; }
    public Instant getReturnedAt() { return returnedAt; }
    public void setReturnedAt(Instant value) { returnedAt = value; }
    public String getDeliveryProofUrl() { return deliveryProofUrl; }
    public void setDeliveryProofUrl(String value) { deliveryProofUrl = value; }
    public String getDeliveryProofPublicId() { return deliveryProofPublicId; }
    public void setDeliveryProofPublicId(String value) { deliveryProofPublicId = value; }
    public boolean isCollectsCod() { return collectsCod; }
    public void setCollectsCod(boolean value) { collectsCod = value; }
    public long getCodAmount() { return codAmount; }
    public void setCodAmount(long value) { codAmount = value; }
    public boolean isInventoryRestored() { return inventoryRestored; }
    public void setInventoryRestored(boolean value) { inventoryRestored = value; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OrderFulfillmentEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return EntityIdentity.hashCode(this); }
}
