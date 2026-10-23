package com.utewedding.app.event;

import com.utewedding.app.service.impl.OutboxService;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Creates durable refresh signals after order and fulfillment changes commit. */
@Component
public class AdminAttentionEventListener {
    private final OutboxService outbox;

    public AdminAttentionEventListener(OutboxService outbox) {
        this.outbox = outbox;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        String number = event.orderNumber() == null || event.orderNumber().isBlank()
                ? "unknown:" + event.eventId() : event.orderNumber();
        outbox.enqueueAfterCommit("ADMIN_ATTENTION_CHANGED", "Order", null,
                "admin-attention:order-created:" + number, "{}", null);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFulfillmentChanged(FulfillmentChangedEvent event) {
        outbox.enqueueAfterCommit("ADMIN_ATTENTION_CHANGED", "Fulfillment", event.fulfillmentId(),
                "admin-attention:fulfillment:" + event.eventId(),
                "{}", null);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAttentionChanged(AdminAttentionChangedEvent event) {
        outbox.enqueueAfterCommit("ADMIN_ATTENTION_CHANGED", event.resourceType(), event.resourceId(),
                "admin-attention:" + event.eventId(), "{}", null);
    }
}
