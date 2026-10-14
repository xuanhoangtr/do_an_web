package com.utewedding.app.dto.response;

import java.time.Instant;

public record FulfillmentTimelineEvent(
        String subject,
        String fromStatus,
        String toStatus,
        Instant changedAt,
        java.util.UUID actorId,
        String note) {

    public FulfillmentTimelineEvent(String subject, String fromStatus, String toStatus, Instant changedAt) {
        this(subject, fromStatus, toStatus, changedAt, null, null);
    }
}
