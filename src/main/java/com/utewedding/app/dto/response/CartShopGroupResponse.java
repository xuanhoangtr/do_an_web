package com.utewedding.app.dto.response;

import java.util.List;
import java.util.UUID;

/** A cart subset owned by one resolved shop; group totals never include another shop's rows. */
public record CartShopGroupResponse(
        UUID shopId,
        String shopName,
        List<CartItemResponse> items,
        long subtotal,
        long discountAmount,
        long totalAmount,
        boolean checkoutAvailable,
        String unavailableReason,
        String selectionFingerprint) {

    public CartShopGroupResponse {
        shopName = shopName == null || shopName.isBlank() ? "Cửa hàng chưa xác định" : shopName;
        items = items == null ? List.of() : List.copyOf(items);
        unavailableReason = checkoutAvailable ? null : unavailableReason;
    }
}
