package com.utewedding.app.event;

import java.util.UUID;

/** Published inside the wish transaction and handled only after commit. */
public record WeddingWishCreatedEvent(
        UUID weddingId,
        UUID wishId,
        UUID ownerId,
        String senderName,
        String message) {
}
