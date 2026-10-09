package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.ProductReviewStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_reviews", indexes = {
        @Index(name = "idx_product_reviews_product_status_created", columnList = "product_id, status, created_at"),
        @Index(name = "idx_product_reviews_user_product", columnList = "user_id, product_id"),
        @Index(name = "idx_product_reviews_order_status", columnList = "order_id, status")
})
public class ProductReviewEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(nullable = false) private int rating;
    @Column(nullable = false, length = 2000) private String content;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ProductReviewStatus status = ProductReviewStatus.PENDING;
    @Column(name = "moderated_by_id") private UUID moderatedById;
    @Column(name = "moderated_at") private Instant moderatedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public void setProductId(UUID value) { productId = value; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID value) { orderId = value; }
    public int getRating() { return rating; }
    public void setRating(int value) { rating = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public ProductReviewStatus getStatus() { return status; }
    public void setStatus(ProductReviewStatus value) { status = value; }
    public UUID getModeratedById() { return moderatedById; }
    public void setModeratedById(UUID value) { moderatedById = value; }
    public Instant getModeratedAt() { return moderatedAt; }
    public void setModeratedAt(Instant value) { moderatedAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    @Override public boolean equals(Object other) { return other instanceof ProductReviewEntity that && EntityIdentity.same(this, other, id, that.id); }
    @Override public int hashCode() { return EntityIdentity.hashCode(this); }
}
