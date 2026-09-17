package com.utewedding.app.dto.response;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String type,
        String title,
        String body,
        String targetUrl,
        Instant readAt,
        Instant createdAt) {
    public boolean unread() {
        return readAt == null;
    }
}
