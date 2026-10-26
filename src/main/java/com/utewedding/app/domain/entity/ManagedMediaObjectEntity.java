package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.ManagedMediaStatus;
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

@Entity
@Table(name = "managed_media_objects", indexes = {
        @Index(name = "idx_managed_media_cleanup", columnList = "status, next_retry_at, retain_until, id"),
        @Index(name = "idx_managed_media_retention", columnList = "retain_until, legal_hold, status")
})
public class ManagedMediaObjectEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 40) private String provider = "CLOUDINARY";
    @Column(name = "resource_type", nullable = false, length = 20) private String resourceType = "image";
    @Column(name = "delivery_type", nullable = false, length = 30) private String deliveryType = "upload";
    @Column(name = "public_id", nullable = false, length = 512) private String publicId;
    @Column(name = "secure_url", length = 2048) private String secureUrl;
    @Column(name = "detected_mime", length = 80) private String detectedMime;
    @Column(name = "detected_format", length = 20) private String detectedFormat;
    @Column(name = "byte_size") private Long byteSize;
    private Integer width;
    private Integer height;
    @Column(name = "pixel_count") private Long pixelCount;
    @Column(length = 64) private String sha256;
    @Column(nullable = false, length = 40) private String purpose;
    @Column(name = "owner_id") private UUID ownerId;
    @Column(name = "shop_id") private UUID shopId;
    @Column(name = "application_id") private UUID applicationId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ManagedMediaStatus status = ManagedMediaStatus.UPLOADING;
    @Column(name = "retain_until") private Instant retainUntil;
    @Column(name = "legal_hold", nullable = false) private boolean legalHold;
    @Column(name = "legal_hold_reason", length = 500) private String legalHoldReason;
    @Column(name = "retry_count", nullable = false) private int retryCount;
    @Column(name = "next_retry_at") private Instant nextRetryAt;
    @Column(name = "last_error", length = 1000) private String lastError;
    @Version @Column(nullable = false) private long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "deleted_at") private Instant deletedAt;

    @PrePersist
    void initializeTimestamps() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void refreshUpdatedAt() {
        updatedAt = Instant.now();
    }

    public ManagedMediaObjectEntity() {}
    public UUID getId() { return id; }
    public String getProvider() { return provider; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String value) { resourceType = value; }
    public String getDeliveryType() { return deliveryType; }
    public void setDeliveryType(String value) { deliveryType = value; }
    public String getPublicId() { return publicId; }
    public void setPublicId(String value) { publicId = value; }
    public ManagedMediaStatus getStatus() { return status; }
    public void setStatus(ManagedMediaStatus value) { status = value; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID value) { ownerId = value; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public UUID getApplicationId() { return applicationId; }
    public void setApplicationId(UUID value) { applicationId = value; }
    public Instant getRetainUntil() { return retainUntil; }
    public void setRetainUntil(Instant value) { retainUntil = value; }
    public boolean isLegalHold() { return legalHold; }
    public void setLegalHold(boolean value) { legalHold = value; }
    public String getSecureUrl() { return secureUrl; }
    public void setSecureUrl(String value) { secureUrl = value; }
    public String getDetectedMime() { return detectedMime; }
    public void setDetectedMime(String value) { detectedMime = value; }
    public String getDetectedFormat() { return detectedFormat; }
    public void setDetectedFormat(String value) { detectedFormat = value; }
    public Long getByteSize() { return byteSize; }
    public void setByteSize(Long value) { byteSize = value; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer value) { width = value; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer value) { height = value; }
    public Long getPixelCount() { return pixelCount; }
    public void setPixelCount(Long value) { pixelCount = value; }
    public String getSha256() { return sha256; }
    public void setSha256(String value) { sha256 = value; }
    public String getLegalHoldReason() { return legalHoldReason; }
    public void setLegalHoldReason(String value) { legalHoldReason = value; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int value) { retryCount = value; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant value) { nextRetryAt = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { lastError = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant value) { deletedAt = value; }
}
