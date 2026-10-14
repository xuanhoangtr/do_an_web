package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** Durable cross-instance claim for a checkout idempotency key. */
@Entity
@Table(name = "order_idempotency_keys")
public class OrderIdempotencyEntity {

    @Id
    @Column(name = "idempotency_key", length = 120)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "order_id", unique = true)
    private UUID orderId;

    /** SHA-256 hex of the canonical checkout payload. Null only on legacy rows. */
    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderIdempotencyEntity() {
    }

    public OrderIdempotencyEntity(String idempotencyKey, UUID userId) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getUserId() { return userId; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OrderIdempotencyEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other,
                idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
