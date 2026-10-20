package com.utewedding.app.domain.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import java.time.Instant;

@Entity
@Table(name = "support_ticket_participants")
public class SupportTicketParticipantEntity {
    @EmbeddedId private SupportTicketParticipantId id;
    @Column(name = "shop_id") private java.util.UUID shopId;
    @jakarta.persistence.Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt = Instant.now();

    protected SupportTicketParticipantEntity() { }
    public SupportTicketParticipantEntity(SupportTicketParticipantId id) { this(id, null); }
    public SupportTicketParticipantEntity(SupportTicketParticipantId id, java.util.UUID shopId) {
        this.id = id;
        this.shopId = shopId;
    }
    public SupportTicketParticipantId getId() { return id; }
    public java.util.UUID getShopId() { return shopId; }
    public Instant getJoinedAt() { return joinedAt; }
}
