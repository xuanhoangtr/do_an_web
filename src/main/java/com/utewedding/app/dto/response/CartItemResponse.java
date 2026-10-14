package com.utewedding.app.dto.response;

import java.util.UUID;

public record CartItemResponse(
        UUID id,
        UUID productId,
        String productName,
        String sku,
        String imageUrl,
        long unitPrice,
        int quantity,
        int discountPercent,
        long discountAmount,
        long lineTotal,
        String customizationText) {

    public CartItemResponse(UUID id, UUID productId, String productName, String sku,
                            String imageUrl, long unitPrice, int quantity,
                            int discountPercent, long discountAmount, long lineTotal) {
        this(id, productId, productName, sku, imageUrl, unitPrice, quantity,
                discountPercent, discountAmount, lineTotal, null);
    }
}
