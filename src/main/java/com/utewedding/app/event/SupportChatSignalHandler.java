package com.utewedding.app.event;

import com.utewedding.app.service.impl.SupportChatService.SupportChatEvent;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Sends metadata-only private signals after the database transaction commits. */
@Component
public class SupportChatSignalHandler {
    private final SimpMessagingTemplate messaging;
    private com.utewedding.app.service.impl.SupportChatRecipientResolver recipients;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRecipients(com.utewedding.app.service.impl.SupportChatRecipientResolver recipients) { this.recipients = recipients; }
    public SupportChatSignalHandler(SimpMessagingTemplate messaging) { this.messaging = messaging; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(SupportChatEvent event) {
        SupportChatSignal signal = new SupportChatSignal(event.ticketId(), event.type(), event.actorId(),
                event.actorName(), event.active(), Instant.now());
        var ticket = recipients == null ? null : recipients.ticket(event.ticketId());
        Set<java.util.UUID> targets = new LinkedHashSet<>(
                event.recipients() == null ? Set.of() : event.recipients());
        if (recipients != null && ticket != null
                && ticket.getTicketKind() == com.utewedding.app.domain.SupportTicketKind.SHOP) {
            // Rebuild the current authorized audience so vendor, customer and
            // global administrators receive signals regardless of which service
            // emitted the change. MANAGER and unrelated shop owners stay excluded.
            targets.addAll(recipients.recipients(ticket));
        }
        for (var recipient : targets) {
            if (recipients != null && !recipients.allowed(ticket, recipient)) continue;
            messaging.convertAndSendToUser(recipient.toString(), "/queue/support-chat", signal);
        }
    }

    public record SupportChatSignal(java.util.UUID ticketId, String type, java.util.UUID actorId,
                                    String actorName, boolean active, Instant occurredAt) { }
}
