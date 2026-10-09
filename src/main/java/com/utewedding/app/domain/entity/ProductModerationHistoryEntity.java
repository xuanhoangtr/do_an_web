package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.ProductModerationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_moderation_history")
public class ProductModerationHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private ProductModerationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private ProductModerationStatus toStatus;

    @Column(length = 1000)
    private String reason;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected ProductModerationHistoryEntity() { }

    public ProductModerationHistoryEntity(UUID productId, UUID shopId,
                                          ProductModerationStatus fromStatus,
                                          ProductModerationStatus toStatus,
                                          String reason, UUID actorUserId) {
        this.productId = productId;
        this.shopId = shopId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.actorUserId = actorUserId;
    }

    @PrePersist
    void onCreate() { if (occurredAt == null) occurredAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public UUID getShopId() { return shopId; }
    public ProductModerationStatus getFromStatus() { return fromStatus; }
    public ProductModerationStatus getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public UUID getActorUserId() { return actorUserId; }
    public Instant getOccurredAt() { return occurredAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ProductModerationHistoryEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return EntityIdentity.hashCode(this); }
}
