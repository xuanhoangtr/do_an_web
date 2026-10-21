package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_shop_chat_contacts")
public class CustomerShopChatContactEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId;

    @Column(name = "last_ticket_id")
    private UUID lastTicketId;

    @Column(name = "first_opened_at", nullable = false, updatable = false)
    private Instant firstOpenedAt;

    @Column(name = "last_opened_at", nullable = false)
    private Instant lastOpenedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerShopChatContactEntity() { }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (firstOpenedAt == null) firstOpenedAt = now;
        if (lastOpenedAt == null) lastOpenedAt = now;
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID value) { customerId = value; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public UUID getLastTicketId() { return lastTicketId; }
    public void setLastTicketId(UUID value) { lastTicketId = value; }
    public Instant getFirstOpenedAt() { return firstOpenedAt; }
    public void setFirstOpenedAt(Instant value) { firstOpenedAt = value; }
    public Instant getLastOpenedAt() { return lastOpenedAt; }
    public void setLastOpenedAt(Instant value) { lastOpenedAt = value; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
