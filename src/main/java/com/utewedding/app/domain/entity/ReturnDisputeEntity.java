package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.ReturnDisputeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "return_disputes", indexes = {
        @Index(name = "idx_return_disputes_case_status", columnList = "return_case_id, status")
})
public class ReturnDisputeEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "return_case_id", nullable = false) private UUID returnCaseId;
    @Column(name = "opened_by", nullable = false) private UUID openedBy;
    @Column(nullable = false, length = 1000) private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReturnDisputeStatus status = ReturnDisputeStatus.OPEN;
    @Column(length = 1000) private String decision;
    @Column(name = "decision_actor") private UUID decisionActor;
    @Column(name = "target_status", length = 32) private String targetStatus;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "decided_at") private Instant decidedAt;

    protected ReturnDisputeEntity() {}
    public ReturnDisputeEntity(UUID caseId, UUID openedBy, String reason) { this.returnCaseId = caseId; this.openedBy = openedBy; this.reason = reason; this.createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getReturnCaseId() { return returnCaseId; }
    public UUID getOpenedBy() { return openedBy; }
    public ReturnDisputeStatus getStatus() { return status; }
    public void setStatus(ReturnDisputeStatus value) { status = value; }
    public String getReason() { return reason; }
    public String getDecision() { return decision; }
    public void setDecision(String value) { decision = value; }
    public UUID getDecisionActor() { return decisionActor; }
    public void setDecisionActor(UUID value) { decisionActor = value; }
    public String getTargetStatus() { return targetStatus; }
    public void setTargetStatus(String value) { targetStatus = value; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant value) { decidedAt = value; }
}