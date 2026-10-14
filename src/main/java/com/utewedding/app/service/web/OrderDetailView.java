package com.utewedding.app.service.web;

import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.OverallFulfillmentStatus;
import java.time.Instant;
import java.util.List;

public record OrderDetailView(
        List<Item> items,
        List<StatusTransition> statusHistory,
        long productDiscountTotal) {

    public boolean canCancel(OrderStatus status, OverallFulfillmentStatus fulfillmentStatus) {
        if (status != OrderStatus.PENDING) return false;
        return fulfillmentStatus != OverallFulfillmentStatus.SHIPPING
                && fulfillmentStatus != OverallFulfillmentStatus.PICKUP_PENDING
                && fulfillmentStatus != OverallFulfillmentStatus.PICKED_UP
                && fulfillmentStatus != OverallFulfillmentStatus.IN_TRANSIT
                && fulfillmentStatus != OverallFulfillmentStatus.PARTIALLY_DELIVERED
                && fulfillmentStatus != OverallFulfillmentStatus.PARTIALLY_DELIVERED_RETURNED
                && fulfillmentStatus != OverallFulfillmentStatus.DELIVERED
                && fulfillmentStatus != OverallFulfillmentStatus.DELIVERY_FAILED
                && fulfillmentStatus != OverallFulfillmentStatus.RETURNING
                && fulfillmentStatus != OverallFulfillmentStatus.RETURNED;
    }

    public record Item(
            String productName,
            String sku,
            String imageUrl,
            int quantity,
            long unitPrice,
            long discountAmount,
            long lineTotal) {
    }

    public record StatusTransition(String subject, String fromStatus, String toStatus, Instant changedAt) {
    }
}
