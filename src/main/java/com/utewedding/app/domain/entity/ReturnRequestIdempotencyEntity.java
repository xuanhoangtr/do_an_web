package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "return_request_idempotency")
@IdClass(ReturnRequestIdempotencyEntity.Key.class)
public class ReturnRequestIdempotencyEntity {
    @Id @Column(name = "customer_id", nullable = false) private UUID customerId;
    @Id @Column(name = "idempotency_key", nullable = false, length = 190) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(name = "return_case_id", unique = true) private UUID returnCaseId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected ReturnRequestIdempotencyEntity() {}
    public ReturnRequestIdempotencyEntity(String key, UUID customerId, String hash) {
        this.idempotencyKey = key; this.customerId = customerId; this.requestHash = hash; this.createdAt = Instant.now();
    }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getCustomerId() { return customerId; }
    public String getRequestHash() { return requestHash; }
    public UUID getReturnCaseId() { return returnCaseId; }
    public void setReturnCaseId(UUID value) { returnCaseId = value; }

    public static final class Key implements Serializable {
        private static final long serialVersionUID = 1L;
        private UUID customerId;
        private String idempotencyKey;

        public Key() { }
        public Key(UUID customerId, String idempotencyKey) {
            this.customerId = customerId;
            this.idempotencyKey = idempotencyKey;
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return java.util.Objects.equals(customerId, key.customerId)
                    && java.util.Objects.equals(idempotencyKey, key.idempotencyKey);
        }
        @Override public int hashCode() { return java.util.Objects.hash(customerId, idempotencyKey); }
    }
}
