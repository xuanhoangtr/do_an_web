package com.utewedding.app.service.impl;

import com.utewedding.app.domain.entity.OutboxEventEntity;
import com.utewedding.app.integration.NotificationService;
import com.utewedding.app.service.port.OutboxDispatcher;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SupportNotificationDispatcher implements OutboxDispatcher {
    private final NotificationService notifications;
    public SupportNotificationDispatcher(NotificationService notifications) { this.notifications = notifications; }
    @Override public boolean supports(String type) { return SupportService.NOTIFICATION_EVENT.equals(type); }
    @Override public void dispatch(OutboxEventEntity event) {
        String[] parts = event.getPayload().split("\\|", 3);
        if (parts.length != 3) throw new IllegalArgumentException("Invalid support notification payload");
        notifications.notifySupport(UUID.fromString(parts[0]), UUID.fromString(parts[1]), parts[2]);
    }
}
