package com.utewedding.app.domain.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "support_ticket_reads")
public class SupportTicketReadEntity {
    @EmbeddedId private SupportTicketReadId id;
    @jakarta.persistence.Column(name = "shop_id") private UUID shopId;
    @jakarta.persistence.Column(name = "last_read_at", nullable = false)
    private Instant lastReadAt;

    protected SupportTicketReadEntity() { }
    public SupportTicketReadEntity(SupportTicketReadId id, Instant lastReadAt) {
        this(id, null, lastReadAt);
    }
    public SupportTicketReadEntity(SupportTicketReadId id, UUID shopId, Instant lastReadAt) {
        this.id = id;
        this.shopId = shopId;
        this.lastReadAt = lastReadAt;
    }
    public SupportTicketReadId getId() { return id; }
    public UUID getShopId() { return shopId; }
    public Instant getLastReadAt() { return lastReadAt; }
    public void setLastReadAt(Instant value) { lastReadAt = value; }
}
