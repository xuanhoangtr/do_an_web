package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.SupportCategory;
import com.utewedding.app.domain.SupportPriority;
import com.utewedding.app.domain.SupportTicketStatus;
import com.utewedding.app.domain.SupportTicketKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "support_tickets")
public class SupportTicketEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ticket_number", nullable = false, unique = true, length = 40)
    private String ticketNumber;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_kind", nullable = false, length = 24)
    private SupportTicketKind ticketKind = SupportTicketKind.PLATFORM_SUPPORT;

    @Column(name = "shop_id")
    private UUID shopId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupportCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupportPriority priority = SupportPriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SupportTicketStatus status = SupportTicketStatus.OPEN;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(name = "order_id") private UUID orderId;
    @Column(name = "payment_id") private UUID paymentId;
    @Column(name = "return_case_id") private UUID returnCaseId;
    @Column(name = "return_dispute_id") private UUID returnDisputeId;
    @Column(name = "fulfillment_id") private UUID fulfillmentId;
    @Column(name = "wedding_id") private UUID weddingId;
    @Column(name = "created_by_id") private UUID createdById;
    @Column(name = "assigned_agent_id") private UUID assignedAgentId;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "closed_at") private Instant closedAt;
    @Column(name = "last_activity_at", nullable = false) private Instant lastActivityAt;
    @Version private long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist
    void create() {
        Instant now = Instant.now();
        if (ticketNumber == null) {
            ticketNumber = "SUP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        }
        createdAt = updatedAt = lastActivityAt = now;
    }

    @PreUpdate void update() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getTicketNumber() { return ticketNumber; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID value) { customerId = value; }
    public SupportTicketKind getTicketKind() { return ticketKind; }
    public void setTicketKind(SupportTicketKind value) { ticketKind = value; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public SupportCategory getCategory() { return category; }
    public void setCategory(SupportCategory value) { category = value; }
    public SupportPriority getPriority() { return priority; }
    public void setPriority(SupportPriority value) { priority = value; }
    public SupportTicketStatus getStatus() { return status; }
    public void setStatus(SupportTicketStatus value) { status = value; }
    public String getSubject() { return subject; }
    public void setSubject(String value) { subject = value; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public UUID getPaymentId() { return paymentId; }
    public void setPaymentId(UUID value) { paymentId = value; }
    public UUID getReturnCaseId() { return returnCaseId; }
    public void setReturnCaseId(UUID value) { returnCaseId = value; }
    public UUID getReturnDisputeId() { return returnDisputeId; }
    public void setReturnDisputeId(UUID value) { returnDisputeId = value; }
    public UUID getFulfillmentId() { return fulfillmentId; }
    public void setFulfillmentId(UUID value) { fulfillmentId = value; }
    public UUID getWeddingId() { return weddingId; }
    public void setWeddingId(UUID value) { weddingId = value; }
    public UUID getCreatedById() { return createdById; }
    public void setCreatedById(UUID value) { createdById = value; }
    public UUID getAssignedAgentId() { return assignedAgentId; }
    public void setAssignedAgentId(UUID value) { assignedAgentId = value; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant value) { resolvedAt = value; }
    public void setClosedAt(Instant value) { closedAt = value; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public void touch() { lastActivityAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
}
