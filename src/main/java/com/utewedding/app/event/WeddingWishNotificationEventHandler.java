package com.utewedding.app.event;

import com.utewedding.app.integration.NotificationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** External wish notifications must never be observed before the DB commits. */
@Component
public class WeddingWishNotificationEventHandler {

    private final NotificationService notificationService;

    public WeddingWishNotificationEventHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWishCreated(WeddingWishCreatedEvent event) {
        notificationService.notifyWeddingWish(event.ownerId(), event.weddingId(), event.wishId(),
                event.senderName(), event.message());
    }
}
