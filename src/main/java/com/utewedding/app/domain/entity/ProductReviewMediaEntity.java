package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Cloudinary image attached to a product review. */
@Entity
@Table(name = "product_review_media")
public class ProductReviewMediaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "managed_media_id")
    private UUID managedMediaId;

    @Column(name = "public_id", length = 512)
    private String publicId;

    @Column(name = "secure_url", nullable = false, length = 2048)
    private String secureUrl;

    @Column(name = "mime_type", nullable = false, length = 80)
    private String mimeType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getReviewId() { return reviewId; }
    public void setReviewId(UUID value) { reviewId = value; }
    public UUID getManagedMediaId() { return managedMediaId; }
    public void setManagedMediaId(UUID value) { managedMediaId = value; }
    public String getPublicId() { return publicId; }
    public void setPublicId(String value) { publicId = value; }
    public String getSecureUrl() { return secureUrl; }
    public void setSecureUrl(String value) { secureUrl = value; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String value) { mimeType = value; }
    public long getByteSize() { return byteSize; }
    public void setByteSize(long value) { byteSize = value; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ProductReviewMediaEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return EntityIdentity.hashCode(this); }
}
