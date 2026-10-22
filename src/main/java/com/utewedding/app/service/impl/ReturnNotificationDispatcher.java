package com.utewedding.app.service.impl;
import com.utewedding.app.domain.entity.OutboxEventEntity;
import com.utewedding.app.integration.NotificationService;
import com.utewedding.app.service.port.OutboxDispatcher;
import java.util.UUID;
import org.springframework.stereotype.Component;
@Component
public class ReturnNotificationDispatcher implements OutboxDispatcher {
    private final NotificationService notifications;
    public ReturnNotificationDispatcher(NotificationService notifications){this.notifications=notifications;}
    @Override public boolean supports(String event){return "RETURN_RESOLVED".equals(event);}
    @Override public void dispatch(OutboxEventEntity event){String[] ids=event.getPayload().split("\\|",2);notifications.notifyReturn(UUID.fromString(ids[0]),UUID.fromString(ids[1]));}
}
