package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.DeliveryAttemptOutcome;
import com.utewedding.app.domain.EntityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_attempts", indexes = {
        @Index(name = "idx_delivery_attempts_fulfillment", columnList = "fulfillment_id, attempted_at"),
        @Index(name = "idx_delivery_attempts_shipper", columnList = "shipper_id, attempted_at")
})
public class DeliveryAttemptEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "fulfillment_id", nullable = false)
    private UUID fulfillmentId;
    @Column(name = "shipper_id", nullable = false)
    private UUID shipperId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeliveryAttemptOutcome outcome;
    @Column(name = "reason_code", length = 40)
    private String reasonCode;
    @Column(length = 1000)
    private String note;
    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "proof_url", length = 2048)
    private String proofUrl;
    @Column(name = "proof_public_id", length = 512)
    private String proofPublicId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (attemptedAt == null) attemptedAt = Instant.now();
        createdAt = attemptedAt;
    }

    public UUID getId() { return id; }
    public UUID getFulfillmentId() { return fulfillmentId; }
    public void setFulfillmentId(UUID value) { fulfillmentId = value; }
    public UUID getShipperId() { return shipperId; }
    public void setShipperId(UUID value) { shipperId = value; }
    public DeliveryAttemptOutcome getOutcome() { return outcome; }
    public void setOutcome(DeliveryAttemptOutcome value) { outcome = value; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String value) { reasonCode = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public Instant getAttemptedAt() { return attemptedAt; }
    public void setAttemptedAt(Instant value) { attemptedAt = value; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant value) { nextAttemptAt = value; }
    public String getProofUrl() { return proofUrl; }
    public void setProofUrl(String value) { proofUrl = value; }
    public String getProofPublicId() { return proofPublicId; }
    public void setProofPublicId(String value) { proofPublicId = value; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DeliveryAttemptEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return EntityIdentity.hashCode(this); }
}
