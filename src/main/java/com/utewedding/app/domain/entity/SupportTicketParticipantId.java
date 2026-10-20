package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SupportTicketParticipantId implements Serializable {
    @Column(name = "ticket_id") private UUID ticketId;
    @Column(name = "user_id") private UUID userId;

    protected SupportTicketParticipantId() { }

    public SupportTicketParticipantId(UUID ticketId, UUID userId) {
        this.ticketId = ticketId;
        this.userId = userId;
    }

    public UUID getTicketId() { return ticketId; }
    public UUID getUserId() { return userId; }

    @Override public boolean equals(Object value) {
        return value instanceof SupportTicketParticipantId other
                && Objects.equals(ticketId, other.ticketId) && Objects.equals(userId, other.userId);
    }

    @Override public int hashCode() { return Objects.hash(ticketId, userId); }
}
