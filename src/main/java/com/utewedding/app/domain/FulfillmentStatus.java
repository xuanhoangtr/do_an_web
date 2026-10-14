package com.utewedding.app.domain;

/**
 * Fulfillment lifecycle, deliberately separate from the payment-only
 * {@link OrderStatus} lifecycle.
 */
public enum FulfillmentStatus {
    CONFIRMED,
    PROCESSING,
    PICKUP_PENDING,
    PICKED_UP,
    IN_TRANSIT,
    DELIVERED,
    DELIVERY_FAILED,
    RETRY_DELIVERY,
    RETURNING,
    /** Legacy value retained so old rows remain readable. */
    CANCELLED,
    /** Legacy value retained so old rows remain readable. */
    RETURNED,
    /** Source compatibility only. V54 migrates every persisted value to IN_TRANSIT. */
    @Deprecated
    SHIPPING;

    public boolean canTransitionTo(FulfillmentStatus target) {
        if (target == null || target == this) return target == this;
        return (this == CONFIRMED && target == PROCESSING)
                || (this == CONFIRMED && target == CANCELLED)
                || (this == PROCESSING && target == PICKUP_PENDING)
                || (this == PICKUP_PENDING && target == PICKED_UP)
                || (this == PICKUP_PENDING && target == CANCELLED)
                || (this == PICKED_UP && target == IN_TRANSIT)
                || (this == IN_TRANSIT && target == DELIVERED)
                || (this == IN_TRANSIT && target == DELIVERY_FAILED)
                || (this == IN_TRANSIT && target == RETURNING)
                || (this == DELIVERY_FAILED && target == RETRY_DELIVERY)
                || (this == DELIVERY_FAILED && target == RETURNING)
                || (this == RETRY_DELIVERY && target == IN_TRANSIT)
                || (this == RETRY_DELIVERY && target == DELIVERY_FAILED)
                || (this == RETRY_DELIVERY && target == RETURNING)
                || (this == PROCESSING && target == CANCELLED)
                // Read-only compatibility for objects created by old unit tests.
                || (this == SHIPPING && (target == DELIVERED || target == DELIVERY_FAILED))
                || (this == RETURNING && target == RETURNED);
    }

    public boolean blocksCodReassignment() {
        return this == PICKED_UP || this == IN_TRANSIT || this == SHIPPING || this == DELIVERED
                || this == DELIVERY_FAILED || this == RETRY_DELIVERY || this == RETURNING || this == RETURNED;
    }

    public boolean hasLeftTheWarehouse() {
        return this == PICKED_UP || this == IN_TRANSIT || this == SHIPPING || this == DELIVERED
                || this == DELIVERY_FAILED || this == RETRY_DELIVERY || this == RETURNING || this == RETURNED;
    }

    public boolean isTerminal() { return this == DELIVERED || this == RETURNED || this == CANCELLED; }
}
