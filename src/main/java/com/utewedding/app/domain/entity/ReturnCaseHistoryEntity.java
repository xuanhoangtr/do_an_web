package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "return_case_history", indexes = {
        @Index(name = "idx_return_case_history_case_created", columnList = "return_case_id, created_at")
})
public class ReturnCaseHistoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "return_case_id", nullable = false) private UUID returnCaseId;
    @Column(name = "actor_id") private UUID actorId;
    @Column(name = "actor_role", length = 30) private String actorRole;
    @Column(name = "from_status", length = 32) private String fromStatus;
    @Column(name = "to_status", nullable = false, length = 32) private String toStatus;
    @Column(name = "request_id", length = 120) private String requestId;
    @Column(nullable = false, columnDefinition = "jsonb") private String metadata = "{}";
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ReturnCaseHistoryEntity() {}
    public ReturnCaseHistoryEntity(UUID caseId, UUID actorId, String actorRole,
                                   String fromStatus, String toStatus, String requestId, String metadata) {
        this.returnCaseId = caseId; this.actorId = actorId; this.actorRole = actorRole;
        this.fromStatus = fromStatus; this.toStatus = toStatus; this.requestId = requestId;
        if (metadata != null) this.metadata = metadata;
        this.createdAt = Instant.now();
    }
    public Instant getCreatedAt(){return createdAt;}
    public UUID getActorId(){return actorId;}
    public String getActorRole(){return actorRole;}
    public UUID getId() { return id; }
    public UUID getReturnCaseId() { return returnCaseId; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
}