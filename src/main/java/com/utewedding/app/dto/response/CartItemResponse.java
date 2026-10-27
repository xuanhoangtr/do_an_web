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
        String customizationText,
        UUID shopId,
        String shopName,
        boolean checkoutAvailable,
        String unavailableReason) {

    public CartItemResponse(UUID id, UUID productId, String productName, String sku,
                            String imageUrl, long unitPrice, int quantity,
                            int discountPercent, long discountAmount, long lineTotal,
                            String customizationText) {
        this(id, productId, productName, sku, imageUrl, unitPrice, quantity,
                discountPercent, discountAmount, lineTotal, customizationText,
                null, "Cửa hàng chưa xác định", false,
                "Không xác định được cửa hàng của sản phẩm.");
    }

    public CartItemResponse(UUID id, UUID productId, String productName, String sku,
                            String imageUrl, long unitPrice, int quantity,
                            int discountPercent, long discountAmount, long lineTotal) {
        this(id, productId, productName, sku, imageUrl, unitPrice, quantity,
                discountPercent, discountAmount, lineTotal, null);
    }
}
