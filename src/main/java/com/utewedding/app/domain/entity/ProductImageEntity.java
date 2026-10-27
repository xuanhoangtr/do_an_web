package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Product image row kept separate from the product aggregate to avoid N+1 lazy graphs. */
@Entity
@Table(name = "product_images", indexes = {
        @Index(name = "idx_product_images_product_primary_order",
                columnList = "product_id, is_primary, display_order")
})
public class ProductImageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "image_url", nullable = false, length = 2048)
    private String imageUrl;

    @Column(name = "managed_media_id")
    private UUID managedMediaId;

    @Column(name = "alt_text", length = 300)
    private String altText;

    @Column(name = "media_type", length = 40)
    private String mediaType = "IMAGE";

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "is_primary", nullable = false)
    private boolean primaryImage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public void setProductId(UUID value) { productId = value; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID value) { shopId = value; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String value) { imageUrl = value; }
    public UUID getManagedMediaId() { return managedMediaId; }
    public void setManagedMediaId(UUID value) { managedMediaId = value; }
    public String getAltText() { return altText; }
    public void setAltText(String value) { altText = value; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String value) { mediaType = value; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int value) { displayOrder = value; }
    public boolean isPrimaryImage() { return primaryImage; }
    public void setPrimaryImage(boolean value) { primaryImage = value; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ProductImageEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
