package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_favorites", uniqueConstraints = @UniqueConstraint(name = "uk_product_favorites_user_product", columnNames = {"user_id", "product_id"}))
public class ProductFavoriteEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    protected ProductFavoriteEntity() {}
    public ProductFavoriteEntity(UUID userId, UUID productId) { this.userId = userId; this.productId = productId; this.createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getProductId() { return productId; }
    public Instant getCreatedAt() { return createdAt; }
    @Override public boolean equals(Object other) { return other instanceof ProductFavoriteEntity that && EntityIdentity.same(this, other, id, that.id); }
    @Override public int hashCode() { return EntityIdentity.hashCode(this); }
}
