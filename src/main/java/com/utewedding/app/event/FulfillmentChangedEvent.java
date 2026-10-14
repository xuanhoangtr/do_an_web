package com.utewedding.app.event;

import com.utewedding.app.domain.FulfillmentStatus;
import java.util.UUID;

/** Published inside the transaction and consumed only after commit. */
public record FulfillmentChangedEvent(
        UUID eventId,
        Type type,
        UUID fulfillmentId,
        UUID orderId,
        UUID customerId,
        UUID shipperId,
        FulfillmentStatus status) {
    public FulfillmentChangedEvent(Type type, UUID fulfillmentId, UUID orderId, UUID customerId,
                                   UUID shipperId, FulfillmentStatus status) {
        this(UUID.randomUUID(), type, fulfillmentId, orderId, customerId, shipperId, status);
    }

    public enum Type { CREATED, ASSIGNED, STATUS_CHANGED, DELIVERED }
}
