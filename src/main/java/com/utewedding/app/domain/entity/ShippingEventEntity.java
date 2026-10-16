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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "shipping_events")
public class ShippingEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "shipment_id")
    private UUID shipmentId;
    @Column(name = "provider_account_id")
    private UUID providerAccountId;
    @Column(name = "provider_shop_id", length = 80)
    private String providerShopId;
    @Column(name = "provider_code", nullable = false, length = 40)
    private String providerCode;
    @Column(name = "provider_event_id", nullable = false, length = 190)
    private String providerEventId;
    @Column(name = "provider_status", nullable = false, length = 80)
    private String providerStatus;
    @Column(name = "canonical_status", length = 30)
    private String canonicalStatus;
    @Column(name = "provider_sequence")
    private Long providerSequence;
    @Column(name = "event_at")
    private Instant eventAt;
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sanitized_payload", nullable = false, columnDefinition = "jsonb")
    private String sanitizedPayload = "{}";
    @Column(nullable = false, length = 30)
    private String outcome;
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
    @Column(name = "applied_at")
    private Instant appliedAt;

    @PrePersist
    void create() {
        if (receivedAt == null) receivedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public void setShipmentId(UUID value) { shipmentId = value; }
    public UUID getProviderAccountId() { return providerAccountId; }
    public void setProviderAccountId(UUID value) { providerAccountId = value; }
    public String getProviderShopId() { return providerShopId; }
    public void setProviderShopId(String value) { providerShopId = value; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String value) { providerCode = value; }
    public String getProviderEventId() { return providerEventId; }
    public void setProviderEventId(String value) { providerEventId = value; }
    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String value) { providerStatus = value; }
    public String getCanonicalStatus() { return canonicalStatus; }
    public void setCanonicalStatus(String value) { canonicalStatus = value; }
    public Long getProviderSequence() { return providerSequence; }
    public void setProviderSequence(Long value) { providerSequence = value; }
    public Instant getEventAt() { return eventAt; }
    public void setEventAt(Instant value) { eventAt = value; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String value) { payloadHash = value; }
    public String getSanitizedPayload() { return sanitizedPayload; }
    public void setSanitizedPayload(String value) { sanitizedPayload = value; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String value) { outcome = value; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String value) { rejectionReason = value; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant value) { appliedAt = value; }
}
