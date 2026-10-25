package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.ShopPayoutOperationType;
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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** Append-only idempotency and audit target for destination and payout commands. */
@Entity
@Table(name = "shop_payout_operations", indexes = {
        @Index(name = "idx_shop_payout_operations_payout", columnList = "payout_id,created_at"),
        @Index(name = "idx_shop_payout_operations_destination", columnList = "destination_id,created_at")
}, uniqueConstraints = @UniqueConstraint(name = "uq_shop_payout_operation_idempotency",
        columnNames = {"actor_id", "idempotency_key"}))
public class ShopPayoutOperationEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "actor_id", nullable = false, updatable = false) private UUID actorId;
    @Enumerated(EnumType.STRING) @Column(name = "operation_type", nullable = false, length = 32, updatable = false)
    private ShopPayoutOperationType operationType;
    @Column(name = "idempotency_key", nullable = false, length = 190, updatable = false) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false) private String requestHash;
    @Column(name = "destination_id", updatable = false) private UUID destinationId;
    @Column(name = "payout_id", updatable = false) private UUID payoutId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ShopPayoutOperationEntity() { }

    public ShopPayoutOperationEntity(UUID actorId, ShopPayoutOperationType type, String key,
                                     String requestHash, UUID destinationId, UUID payoutId, Instant createdAt) {
        this.actorId = actorId;
        this.operationType = type;
        this.idempotencyKey = key;
        this.requestHash = requestHash;
        this.destinationId = destinationId;
        this.payoutId = payoutId;
        this.createdAt = createdAt;
    }

    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getActorId() { return actorId; }
    public ShopPayoutOperationType getOperationType() { return operationType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public UUID getDestinationId() { return destinationId; }
    public UUID getPayoutId() { return payoutId; }
    public Instant getCreatedAt() { return createdAt; }
}
