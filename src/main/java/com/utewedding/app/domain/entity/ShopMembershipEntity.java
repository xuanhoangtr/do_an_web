package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.ShopMembershipRole;
import com.utewedding.app.domain.ShopMembershipStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shop_memberships")
public class ShopMembershipEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShopMembershipRole role = ShopMembershipRole.OWNER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShopMembershipStatus status = ShopMembershipStatus.ACTIVE;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by_user_id")
    private UUID revokedByUserId;

    @Column(name = "revocation_reason", length = 1000)
    private String revocationReason;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopMembershipEntity() { }

    public ShopMembershipEntity(UUID shopId, UUID userId, ShopMembershipRole role) {
        this.shopId = shopId;
        this.userId = userId;
        this.role = role;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    public ShopMembershipRole getRole() { return role; }
    public void setRole(ShopMembershipRole value) { role = value; }
    public ShopMembershipStatus getStatus() { return status; }
    public void setStatus(ShopMembershipStatus value) { status = value; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant value) { revokedAt = value; }
    public UUID getRevokedByUserId() { return revokedByUserId; }
    public void setRevokedByUserId(UUID value) { revokedByUserId = value; }
    public String getRevocationReason() { return revocationReason; }
    public void setRevocationReason(String value) { revocationReason = value; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ShopMembershipEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return EntityIdentity.hashCode(this); }
}
