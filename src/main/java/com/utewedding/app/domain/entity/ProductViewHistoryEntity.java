package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_view_history")
@IdClass(ProductViewHistoryId.class)
public class ProductViewHistoryEntity {
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "last_viewed_at", nullable = false)
    private Instant lastViewedAt;

    protected ProductViewHistoryEntity() {
    }

    public ProductViewHistoryEntity(UUID userId, UUID productId, Instant lastViewedAt) {
        this.userId = userId;
        this.productId = productId;
        this.lastViewedAt = lastViewedAt;
    }

    public UUID getUserId() { return userId; }
    public UUID getProductId() { return productId; }
    public Instant getLastViewedAt() { return lastViewedAt; }
}
