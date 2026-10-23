package com.utewedding.app.event;

import java.util.UUID;

/** Signals that a committed operation may have changed the admin work queue. */
public record AdminAttentionChangedEvent(String resourceType, UUID resourceId, UUID eventId) {
    public AdminAttentionChangedEvent(String resourceType, UUID resourceId) {
        this(resourceType, resourceId, UUID.randomUUID());
    }
}
