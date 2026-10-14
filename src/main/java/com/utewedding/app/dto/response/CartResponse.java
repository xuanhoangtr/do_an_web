package com.utewedding.app.dto.response;

import java.util.List;
import java.util.UUID;

public record CartResponse(
        UUID id,
        List<CartItemResponse> items,
        long subtotal,
        long discountAmount,
        long totalAmount) {
}
