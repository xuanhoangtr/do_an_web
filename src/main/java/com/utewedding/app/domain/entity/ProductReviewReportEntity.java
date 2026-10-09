package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.EntityIdentity;
import com.utewedding.app.domain.ProductReviewReportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_review_reports", indexes = {
        @Index(name = "idx_review_reports_review_status", columnList = "review_id, status, created_at"),
        @Index(name = "idx_review_reports_reporter", columnList = "reporter_id, created_at")
})
public class ProductReviewReportEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "review_id", nullable = false) private UUID reviewId;
    @Column(name = "reporter_id", nullable = false) private UUID reporterId;
    @Column(nullable = false, length = 1000) private String reason;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private ProductReviewReportStatus status = ProductReviewReportStatus.OPEN;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    public UUID getId() { return id; }
    public UUID getReviewId() { return reviewId; }
    public void setReviewId(UUID value) { reviewId = value; }
    public UUID getReporterId() { return reporterId; }
    public void setReporterId(UUID value) { reporterId = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public ProductReviewReportStatus getStatus() { return status; }
    public void setStatus(ProductReviewReportStatus value) { status = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ProductReviewReportEntity that)) return false;
        return EntityIdentity.same(this, other, id, that.id);
    }
    @Override public int hashCode() { return EntityIdentity.hashCode(this); }
}
