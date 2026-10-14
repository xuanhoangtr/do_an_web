package com.utewedding.app.domain;

import java.util.Collection;

/** Derived status for an order containing zero or more delivery fulfillments. */
public enum OverallFulfillmentStatus {
    NOT_READY,
    CONFIRMED,
    PROCESSING,
    PICKUP_PENDING,
    PICKED_UP,
    IN_TRANSIT,
    PARTIALLY_DELIVERED,
    PARTIALLY_DELIVERED_RETURNED,
    DELIVERED,
    DELIVERY_FAILED,
    RETURNING,
    RETURNED,
    CANCELLED,
    /** Compatibility name for callers compiled against the previous model. */
    @Deprecated SHIPPING;

    public static OverallFulfillmentStatus from(Collection<FulfillmentStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) return NOT_READY;
        java.util.List<FulfillmentStatus> active = statuses.stream()
                .filter(status -> status != null && status != FulfillmentStatus.CANCELLED)
                .toList();
        if (active.isEmpty()) return CANCELLED;
        boolean delivered = active.stream().anyMatch(status -> status == FulfillmentStatus.DELIVERED);
        boolean returned = active.stream().anyMatch(status -> status == FulfillmentStatus.RETURNED);
        if (delivered && returned && active.stream().allMatch(status ->
                status == FulfillmentStatus.DELIVERED || status == FulfillmentStatus.RETURNED)) {
            return PARTIALLY_DELIVERED_RETURNED;
        }
        if (active.stream().anyMatch(status -> status == FulfillmentStatus.RETURNING)) return RETURNING;
        if (active.stream().allMatch(status -> status == FulfillmentStatus.RETURNED)) return RETURNED;
        if (active.stream().anyMatch(status -> status == FulfillmentStatus.DELIVERY_FAILED)
                && active.stream().noneMatch(status -> status == FulfillmentStatus.IN_TRANSIT
                    || status == FulfillmentStatus.RETRY_DELIVERY)) {
            return DELIVERY_FAILED;
        }
        if (active.stream().anyMatch(status -> status == FulfillmentStatus.IN_TRANSIT
                || status == FulfillmentStatus.RETRY_DELIVERY || status == FulfillmentStatus.SHIPPING)) return IN_TRANSIT;
        if (active.stream().anyMatch(status -> status == FulfillmentStatus.PICKED_UP)) return PICKED_UP;
        if (active.stream().anyMatch(status -> status == FulfillmentStatus.PICKUP_PENDING)) return PICKUP_PENDING;
        if (active.stream().allMatch(status -> status == FulfillmentStatus.DELIVERED)) return DELIVERED;
        if (active.stream().anyMatch(status -> status == FulfillmentStatus.DELIVERED)) return PARTIALLY_DELIVERED;
        if (active.stream().anyMatch(status -> status == FulfillmentStatus.PROCESSING)) return PROCESSING;
        if (active.stream().allMatch(status -> status == FulfillmentStatus.CONFIRMED)) return CONFIRMED;
        return NOT_READY;
    }
}
