package com.utewedding.app.dto.response;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.OrderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Deliberately excludes customer name, phone, address, items, and payment details. */
public record PublicOrderTrackingResponse(String orderNumber, OrderStatus status, Instant createdAt,
                                          List<Fulfillment> fulfillments, List<TimelineEvent> timeline,
                                          LocalDate expectedDeliveryFrom, LocalDate expectedDeliveryTo,
                                          int expectedDeliveryMinDays, int expectedDeliveryMaxDays,
                                          String deliveryArea) {
    public PublicOrderTrackingResponse {
        fulfillments = fulfillments == null ? List.of() : List.copyOf(fulfillments);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
    }

    public PublicOrderTrackingResponse(String orderNumber, OrderStatus status, Instant createdAt,
                                       List<Fulfillment> fulfillments) {
        this(orderNumber, status, createdAt, fulfillments, List.of(), null, null, 0, 0, null);
    }

    public record Fulfillment(FulfillmentStatus status, String trackingNumber, Instant updatedAt) { }
    public record TimelineEvent(String title, String detail, Instant occurredAt, boolean warning) { }
}
