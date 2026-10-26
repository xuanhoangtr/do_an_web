package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.MediaAssetKind;
import com.utewedding.app.domain.MediaAssetStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Java media projection of the legacy media_assets row.
 *
 * Cloudinary's secure HTTPS URL is deliberately stored in objectKey: the
 * migration no longer builds local MinIO URLs from this value. publicId is
 * retained as a transient convenience for Cloudinary management operations.
 */
@Entity
@Table(name = "media_assets")
public class MediaAssetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "wedding_id", nullable = false)
    private UUID weddingId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "\"MediaAssetKind\"")
    private MediaAssetKind kind = MediaAssetKind.GALLERY;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "\"MediaAssetStatus\"")
    private MediaAssetStatus status = MediaAssetStatus.READY;

    @Column(name = "object_key", nullable = false, unique = true, length = 512)
    private String objectKey;

    @Column(name = "mime_type", nullable = false, length = 80)
    private String mimeType;

    @Column(name = "byte_size", nullable = false)
    private int byteSize;

    private Integer width;
    private Integer height;

    @Column(name = "alt_text", length = 300)
    private String altText;

    @Column(length = 500)
    private String caption;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "is_visible", nullable = false)
    private boolean visible = true;

    @Column(name = "deletion_attempts", nullable = false)
    private int deletionAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @jakarta.persistence.Transient
    private String publicId;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getWeddingId() { return weddingId; }
    public void setWeddingId(UUID value) { weddingId = value; }
    public MediaAssetKind getKind() { return kind; }
    public void setKind(MediaAssetKind value) { kind = value; }
    public MediaAssetStatus getStatus() { return status; }
    public void setStatus(MediaAssetStatus value) { status = value; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String value) { objectKey = value; }
    public String getSecureUrl() { return objectKey; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String value) { mimeType = value; }
    public int getByteSize() { return byteSize; }
    public void setByteSize(int value) { byteSize = value; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer value) { width = value; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer value) { height = value; }
    public String getAltText() { return altText; }
    public void setAltText(String value) { altText = value; }
    public String getCaption() { return caption; }
    public void setCaption(String value) { caption = value; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int value) { displayOrder = value; }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean value) { visible = value; }
    public int getDeletionAttempts() { return deletionAttempts; }
    public String getPublicId() { return publicId; }
    public void setPublicId(String value) { publicId = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MediaAssetEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
