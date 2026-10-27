package com.utewedding.app.dto.response;

import java.util.List;
import java.util.UUID;

public record CartResponse(
        UUID id,
        List<CartItemResponse> items,
        long subtotal,
        long discountAmount,
        long totalAmount,
        List<CartShopGroupResponse> shopGroups) {

    public CartResponse {
        items = items == null ? List.of() : List.copyOf(items);
        shopGroups = shopGroups == null ? List.of() : List.copyOf(shopGroups);
    }

    public CartResponse(UUID id, List<CartItemResponse> items, long subtotal,
                        long discountAmount, long totalAmount) {
        this(id, items, subtotal, discountAmount, totalAmount, List.of());
    }
}
