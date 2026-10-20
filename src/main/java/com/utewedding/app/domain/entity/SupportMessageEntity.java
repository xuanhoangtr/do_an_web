package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "support_messages")
public class SupportMessageEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "ticket_id", nullable = false) private UUID ticketId;
    @Column(name = "shop_id") private UUID shopId;
    @Column(name = "author_id") private UUID authorId;
    @Column(nullable = false, length = 20) private String visibility = "CUSTOMER";
    @Column(nullable = false, length = 5000) private String body;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    @PrePersist void create() { if (createdAt == null) createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getTicketId() { return ticketId; }
    public void setTicketId(UUID value) { ticketId = value; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public UUID getAuthorId() { return authorId; }
    public void setAuthorId(UUID value) { authorId = value; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String value) { visibility = value; }
    public String getBody() { return body; }
    public void setBody(String value) { body = value; }
    public Instant getCreatedAt() { return createdAt; }
}
