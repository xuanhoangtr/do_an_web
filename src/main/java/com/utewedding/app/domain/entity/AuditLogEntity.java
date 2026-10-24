package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.AuditAction;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only audit record. No controller exposes update/delete operations. */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_actor_created_at", columnList = "actor_id, created_at"),
        @Index(name = "idx_audit_logs_target_created_at", columnList = "target_type, target_id, created_at"),
        @Index(name = "idx_audit_logs_target_ref_created_at", columnList = "target_type, target_ref, created_at"),
        @Index(name = "idx_audit_logs_action_created_at", columnList = "action, created_at"),
        @Index(name = "idx_audit_logs_created_at", columnList = "created_at")
})
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "\"AuditAction\"")
    private AuditAction action;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_role", length = 30)
    private String actorRole;

    @Column(name = "target_type", length = 60)
    private String targetType;

    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "target_ref", length = 80)
    private String targetReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLogEntity() {
    }

    public AuditLogEntity(AuditAction action, UUID actorId, String targetType, UUID targetId,
                          Map<String, Object> metadata, String ipHash, String userAgent,
                          String requestId) {
        this(action, actorId, null, targetType, targetId,
                targetId == null ? null : targetId.toString(), metadata, ipHash, userAgent, requestId);
    }

    public AuditLogEntity(AuditAction action, UUID actorId, String actorRole, String targetType,
                          UUID targetId, String targetReference, Map<String, Object> metadata,
                          String ipHash, String userAgent, String requestId) {
        this.action = action;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetReference = targetReference;
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        this.ipHash = ipHash;
        this.userAgent = userAgent;
        this.requestId = requestId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public AuditAction getAction() { return action; }
    public UUID getActorId() { return actorId; }
    public String getActorRole() { return actorRole; }
    public String getTargetType() { return targetType; }
    public UUID getTargetId() { return targetId; }
    public String getTargetReference() { return targetReference; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getIpHash() { return ipHash; }
    public String getUserAgent() { return userAgent; }
    public String getRequestId() { return requestId; }
    public Instant getCreatedAt() { return createdAt; }

    /** Removes request-level personal context while preserving the immutable event fact and actor linkage. */
    public void redactPersonalContext() {
        metadata = new LinkedHashMap<>();
        ipHash = null;
        userAgent = null;
        requestId = null;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AuditLogEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
