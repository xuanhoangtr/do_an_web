package com.utewedding.app.domain.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ProductViewHistoryId implements Serializable {
    private UUID userId;
    private UUID productId;

    public ProductViewHistoryId() {
    }

    public ProductViewHistoryId(UUID userId, UUID productId) {
        this.userId = userId;
        this.productId = productId;
    }

    public UUID getUserId() { return userId; }
    public UUID getProductId() { return productId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ProductViewHistoryId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(productId, that.productId);
    }

    @Override
    public int hashCode() { return Objects.hash(userId, productId); }
}
