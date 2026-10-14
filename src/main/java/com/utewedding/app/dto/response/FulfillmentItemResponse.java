package com.utewedding.app.dto.response;

import java.util.UUID;

public record FulfillmentItemResponse(
        UUID orderItemId,
        String productName,
        Integer quantity,
        String sku,
        Long unitPrice,
        Long lineTotal,
        String imageUrl) {

    public FulfillmentItemResponse(UUID orderItemId, String productName, Integer quantity) {
        this(orderItemId, productName, quantity, null, null, null, null);
    }
}
