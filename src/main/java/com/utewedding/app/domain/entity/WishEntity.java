package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.WishStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "wishes", indexes = {
        @Index(name = "idx_wishes_wedding_status_deleted_at", columnList = "wedding_id, status, deleted_at"),
        @Index(name = "idx_wishes_wedding_submitted_at", columnList = "wedding_id, submitted_at")
})
@SQLRestriction("deleted_at IS NULL")
public class WishEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "wedding_id", nullable = false)
    private UUID weddingId;
    @Column(name = "sender_name", nullable = false, length = 160)
    private String senderName;
    @Column(nullable = false, length = 2000)
    private String message;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "\"WishStatus\"")
    private WishStatus status = WishStatus.PENDING;
    @Column(name = "guest_id") private UUID guestId;
    @Column(name = "submitter_ip_hash", length = 64) private String submitterIpHash;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "moderated_by_id") private UUID moderatedById;
    @Column(name = "submitted_at", nullable = false) private Instant submittedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "deleted_at") private Instant deletedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        submittedAt = submittedAt == null ? now : submittedAt;
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getWeddingId() { return weddingId; }
    public void setWeddingId(UUID value) { weddingId = value; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String value) { senderName = value; }
    public String getMessage() { return message; }
    public void setMessage(String value) { message = value; }
    public WishStatus getStatus() { return status; }
    public void setStatus(WishStatus value) { status = value; }
    public UUID getGuestId() { return guestId; }
    public void setGuestId(UUID value) { guestId = value; }
    public String getSubmitterIpHash() { return submitterIpHash; }
    public void setSubmitterIpHash(String value) { submitterIpHash = value; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant value) { approvedAt = value; }
    public UUID getModeratedById() { return moderatedById; }
    public void setModeratedById(UUID value) { moderatedById = value; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant value) { submittedAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant value) { deletedAt = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof WishEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
