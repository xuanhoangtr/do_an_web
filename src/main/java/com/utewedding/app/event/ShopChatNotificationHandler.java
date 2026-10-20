package com.utewedding.app.event;

import com.utewedding.app.domain.SupportTicketKind;
import com.utewedding.app.service.impl.*;
import com.utewedding.app.service.impl.SupportChatService.SupportChatEvent;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ShopChatNotificationHandler {
    private final SupportChatRecipientResolver recipients;
    private final OutboxService outbox;
    public ShopChatNotificationHandler(SupportChatRecipientResolver recipients, OutboxService outbox) {
        this.recipients = recipients; this.outbox = outbox;
    }
    @EventListener
    public void messageChanged(SupportChatEvent event) {
        if (!"MESSAGE_CHANGED".equals(event.type())) return;
        var ticket = recipients.ticket(event.ticketId());
        if (ticket == null || ticket.getTicketKind() != SupportTicketKind.SHOP) return;
        String change = UUID.randomUUID().toString();
        for (UUID recipient : recipients.recipients(ticket)) {
            if (recipient.equals(event.actorId()) || !recipients.allowed(ticket, recipient)) continue;
            outbox.enqueue(SupportService.NOTIFICATION_EVENT, "SupportTicket", event.ticketId(),
                    "shop-chat:" + change + ":" + recipient,
                    recipient + "|" + event.ticketId() + "|Bạn có tin nhắn mới từ cuộc trò chuyện cửa hàng.", null);
        }
    }
}
