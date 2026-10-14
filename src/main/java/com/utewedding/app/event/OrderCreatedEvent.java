package com.utewedding.app.event;

import java.util.UUID;

public record OrderCreatedEvent(
        UUID eventId,
        UUID customerUserId,
        String orderNumber,
        long amount,
        String currency) {
    public OrderCreatedEvent(UUID customerUserId, String orderNumber, long amount, String currency) {
        this(UUID.randomUUID(), customerUserId, orderNumber, amount, currency);
    }
}
