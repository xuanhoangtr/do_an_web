package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.ShopAddressKind;
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
@Table(name = "shop_address_heads")
public class ShopAddressHeadEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "shop_id", nullable = false)
    private UUID shopId;
    @Enumerated(EnumType.STRING)
    @Column(name = "address_kind", nullable = false, length = 12)
    private ShopAddressKind addressKind;
    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;
    @Version
    private long version;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopAddressHeadEntity() { }
    public ShopAddressHeadEntity(UUID shopId, ShopAddressKind kind, UUID revisionId) {
        this.shopId = shopId; this.addressKind = kind; this.revisionId = revisionId; this.updatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public ShopAddressKind getAddressKind() { return addressKind; }
    public UUID getRevisionId() { return revisionId; }
    public void setRevisionId(UUID value) { revisionId = value; updatedAt = Instant.now(); }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ShopAddressHeadEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }
    @Override public int hashCode() { return EntityIdentity.hashCode(this); }
}
