package com.utewedding.app.dto.response;

import java.util.UUID;

public record OrderItemResponse(
        UUID productId,
        String productName,
        long unitPrice,
        int quantity,
        long discountAmount,
        long lineTotal) {
}
