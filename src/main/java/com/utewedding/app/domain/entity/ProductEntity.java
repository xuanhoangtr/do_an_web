package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Canonical marketplace aggregate backed by the {@code catalog_products} table. */
@Entity
@Table(name = "catalog_products", indexes = {
        @Index(name = "idx_catalog_products_category_active", columnList = "category_id, active"),
        @Index(name = "idx_catalog_products_created_at", columnList = "created_at")
})
public class ProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(nullable = false, length = 80)
    private String sku;

    @Column(name = "short_description", length = 500)
    private String shortDescription;

    @Column(name = "description", length = 4000)
    private String description;

    @Column(name = "customization_guide", length = 2000)
    private String customizationGuide;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(name = "compare_at_price", precision = 19, scale = 2)
    private BigDecimal compareAtPrice;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    @Column(name = "min_order_quantity", nullable = false)
    private int minOrderQuantity = 1;

    @Column(name = "weight_grams")
    private Integer weightGrams;

    @Column(length = 160)
    private String dimensions;

    @Column(length = 160)
    private String material;

    @Column(name = "is_featured", nullable = false)
    private boolean featured;

    @Column(name = "discount_percent", nullable = false)
    private int discountPercent;

    @Column(name = "is_customizable", nullable = false)
    private boolean customizable;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "average_rating", precision = 3, scale = 2)
    private BigDecimal averageRating = BigDecimal.ZERO;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Primary image convenience column; gallery rows remain in product_images. */
    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @jakarta.persistence.Version
    @Column(nullable = false)
    private long version;

    public ProductEntity() {
    }

    public ProductEntity(Long categoryId, String name, long unitPrice) {
        this.categoryId = categoryId;
        this.name = name;
        this.unitPrice = unitPrice;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (slug == null || slug.isBlank()) {
            String base = slugify(name);
            String shortId = id.toString().replace("-", "").substring(0, 6);
            slug = base.isEmpty() ? "san-pham-" + shortId : base + "-" + shortId;
        }
        if (sku == null || sku.isBlank()) {
            sku = "PRODUCT-" + id.toString().replace("-", "").substring(0, 12).toUpperCase();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = createdAt;
    }

    public static String slugify(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(
                        raw.trim().replace('đ', 'd').replace('Đ', 'D'),
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64).replaceAll("-+$", "");
        }
        return normalized;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long value) { categoryId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getSlug() { return slug; }
    public void setSlug(String value) { slug = value; }
    public String getSku() { return sku; }
    public void setSku(String value) { sku = value; }
    public String getShortDescription() { return shortDescription; }
    public void setShortDescription(String value) { shortDescription = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getCustomizationGuide() { return customizationGuide; }
    public void setCustomizationGuide(String value) { customizationGuide = value; }
    public long getUnitPrice() { return unitPrice; }
    public void setUnitPrice(long value) { unitPrice = value; }
    public BigDecimal getCompareAtPrice() { return compareAtPrice; }
    public void setCompareAtPrice(BigDecimal value) { compareAtPrice = value; }
    public int getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(int value) { stockQuantity = value; }
    public int getMinOrderQuantity() { return minOrderQuantity; }
    public void setMinOrderQuantity(int value) { minOrderQuantity = value; }
    public Integer getWeightGrams() { return weightGrams; }
    public void setWeightGrams(Integer value) { weightGrams = value; }
    public String getDimensions() { return dimensions; }
    public void setDimensions(String value) { dimensions = value; }
    public String getMaterial() { return material; }
    public void setMaterial(String value) { material = value; }
    public boolean isFeatured() { return featured; }
    public void setFeatured(boolean value) { featured = value; }
    public int getDiscountPercent() { return discountPercent; }
    public void setDiscountPercent(int value) { discountPercent = value; }
    public boolean isCustomizable() { return customizable; }
    public void setCustomizable(boolean value) { customizable = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public BigDecimal getAverageRating() { return averageRating; }
    public void setAverageRating(BigDecimal value) { averageRating = value; }
    public int getReviewCount() { return reviewCount; }
    public void setReviewCount(int value) { reviewCount = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String value) { imageUrl = value; }
    public long getVersion() { return version; }
    public void setVersion(long value) { version = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ProductEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return EntityIdentity.hashCode(this); }
}
