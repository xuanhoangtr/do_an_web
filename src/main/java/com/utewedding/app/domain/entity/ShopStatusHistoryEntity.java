package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.ShopStatus;
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
@Table(name = "shop_status_history")
public class ShopStatusHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20)
    private ShopStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private ShopStatus newStatus;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "application_id")
    private UUID applicationId;

    @Column(length = 1000)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected ShopStatusHistoryEntity() { }

    public ShopStatusHistoryEntity(UUID shopId, ShopStatus previousStatus, ShopStatus newStatus,
                                   UUID actorUserId, UUID applicationId, String reason) {
        this.shopId = shopId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.actorUserId = actorUserId;
        this.applicationId = applicationId;
        this.reason = reason;
    }

    @PrePersist
    void onCreate() { if (occurredAt == null) occurredAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public ShopStatus getPreviousStatus() { return previousStatus; }
    public ShopStatus getNewStatus() { return newStatus; }
    public UUID getActorUserId() { return actorUserId; }
    public UUID getApplicationId() { return applicationId; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ShopStatusHistoryEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return EntityIdentity.hashCode(this); }
}
