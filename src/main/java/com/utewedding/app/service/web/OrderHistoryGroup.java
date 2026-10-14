package com.utewedding.app.service.web;

import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.OverallFulfillmentStatus;

/** Presentation grouping for the order history tabs; it is not a business state. */
public enum OrderHistoryGroup {
    ALL("Tất cả"),
    PENDING_PAYMENT("Chờ thanh toán"),
    PREPARING("Đang chuẩn bị"),
    SHIPPING("Đang giao"),
    DELIVERED("Đã giao"),
    CANCELLED_RETURNED("Đã hủy/Hoàn");

    private final String label;

    OrderHistoryGroup(String label) { this.label = label; }

    public String label() { return label; }

    public static OrderHistoryGroup from(OrderStatus orderStatus, OverallFulfillmentStatus fulfillmentStatus) {
        if (orderStatus == OrderStatus.PENDING) return PENDING_PAYMENT;
        if (orderStatus == OrderStatus.CANCELLED || orderStatus == OrderStatus.EXPIRED
                || orderStatus == OrderStatus.REFUNDED
                || fulfillmentStatus == OverallFulfillmentStatus.RETURNING
                || fulfillmentStatus == OverallFulfillmentStatus.RETURNED
                || fulfillmentStatus == OverallFulfillmentStatus.PARTIALLY_DELIVERED_RETURNED) {
            return CANCELLED_RETURNED;
        }
        if (fulfillmentStatus == OverallFulfillmentStatus.CONFIRMED
                || fulfillmentStatus == OverallFulfillmentStatus.PROCESSING
                || fulfillmentStatus == OverallFulfillmentStatus.PICKUP_PENDING) return PREPARING;
        if (fulfillmentStatus == OverallFulfillmentStatus.PICKED_UP
                || fulfillmentStatus == OverallFulfillmentStatus.IN_TRANSIT) return SHIPPING;
        if (fulfillmentStatus == OverallFulfillmentStatus.DELIVERED
                || fulfillmentStatus == OverallFulfillmentStatus.PARTIALLY_DELIVERED) return DELIVERED;
        return ALL;
    }
}
