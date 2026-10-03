package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EventType;
import com.utewedding.app.domain.WeddingStatus;
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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "weddings", indexes = {
        @Index(name = "idx_weddings_owner_deleted_at", columnList = "owner_id, deleted_at"),
        @Index(name = "idx_weddings_owner_status", columnList = "owner_id, status"),
        @Index(name = "idx_weddings_status_published_at", columnList = "status, published_at")
})
@SQLRestriction("deleted_at IS NULL")
public class WeddingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "template_id")
    private UUID templateId;
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private EventType eventType = EventType.WEDDING;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WeddingStatus status = WeddingStatus.DRAFT;
    @Column(length = 60, unique = true)
    private String slug;
    @Column(name = "preview_token", nullable = false, unique = true, length = 64)
    private String previewToken;
    @Column(name = "internal_title", length = 160)
    private String internalTitle;
    @Version
    @Column(nullable = false)
    private int version = 1;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID templateId) { this.templateId = templateId; }
    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }
    public WeddingStatus getStatus() { return status; }
    public void setStatus(WeddingStatus status) { this.status = status; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getPreviewToken() { return previewToken; }
    public void setPreviewToken(String previewToken) { this.previewToken = previewToken; }
    public String getInternalTitle() { return internalTitle; }
    public void setInternalTitle(String internalTitle) { this.internalTitle = internalTitle; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof WeddingEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
