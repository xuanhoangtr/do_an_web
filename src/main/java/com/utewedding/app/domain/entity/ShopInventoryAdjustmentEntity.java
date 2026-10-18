package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shop_inventory_adjustments")
public class ShopInventoryAdjustmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "delta_quantity", nullable = false)
    private int deltaQuantity;

    @Column(name = "quantity_before", nullable = false)
    private int quantityBefore;

    @Column(name = "quantity_after", nullable = false)
    private int quantityAfter;

    @Column(name = "version_before", nullable = false)
    private long versionBefore;

    @Column(name = "version_after", nullable = false)
    private long versionAfter;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ShopInventoryAdjustmentEntity() { }

    public ShopInventoryAdjustmentEntity(UUID shopId, UUID productId, UUID actorUserId, int deltaQuantity,
                                         int quantityBefore, int quantityAfter, long versionBefore,
                                         long versionAfter, String reason) {
        this.shopId = shopId;
        this.productId = productId;
        this.actorUserId = actorUserId;
        this.deltaQuantity = deltaQuantity;
        this.quantityBefore = quantityBefore;
        this.quantityAfter = quantityAfter;
        this.versionBefore = versionBefore;
        this.versionAfter = versionAfter;
        this.reason = reason;
    }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public UUID getProductId() { return productId; }
    public UUID getActorUserId() { return actorUserId; }
    public int getDeltaQuantity() { return deltaQuantity; }
    public int getQuantityBefore() { return quantityBefore; }
    public int getQuantityAfter() { return quantityAfter; }
    public long getVersionBefore() { return versionBefore; }
    public long getVersionAfter() { return versionAfter; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
