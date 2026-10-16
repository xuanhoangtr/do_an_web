package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable proof linking an authenticated GHN event to its tenant shipment. */
@Entity
@Table(name = "shipping_event_reconciliations")
public class ShippingEventReconciliationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shipping_event_id", nullable = false, unique = true, updatable = false)
    private UUID shippingEventId;

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Column(name = "provider_account_id", nullable = false, updatable = false)
    private UUID providerAccountId;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId;

    @Column(name = "provider_shop_id", nullable = false, length = 80, updatable = false)
    private String providerShopId;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Column(name = "reconciled_at", nullable = false, updatable = false)
    private Instant reconciledAt;

    protected ShippingEventReconciliationEntity() { }

    public ShippingEventReconciliationEntity(UUID shippingEventId, UUID shipmentId, UUID providerAccountId,
                                             UUID shopId, String providerShopId, UUID actorUserId) {
        this.shippingEventId = shippingEventId;
        this.shipmentId = shipmentId;
        this.providerAccountId = providerAccountId;
        this.shopId = shopId;
        this.providerShopId = providerShopId;
        this.actorUserId = actorUserId;
    }

    @PrePersist
    void onCreate() {
        if (reconciledAt == null) reconciledAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getShippingEventId() { return shippingEventId; }
    public UUID getShipmentId() { return shipmentId; }
    public UUID getProviderAccountId() { return providerAccountId; }
    public UUID getShopId() { return shopId; }
    public String getProviderShopId() { return providerShopId; }
    public UUID getActorUserId() { return actorUserId; }
    public Instant getReconciledAt() { return reconciledAt; }
}
