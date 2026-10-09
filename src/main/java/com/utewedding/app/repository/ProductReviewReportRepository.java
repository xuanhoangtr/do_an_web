package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ProductReviewReportEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductReviewReportRepository extends JpaRepository<ProductReviewReportEntity, UUID> {
    Optional<ProductReviewReportEntity> findByReviewIdAndReporterId(UUID reviewId, UUID reporterId);
    org.springframework.data.domain.Page<ProductReviewReportEntity> findByStatusOrderByCreatedAtAsc(
            com.utewedding.app.domain.ProductReviewReportStatus status,
            org.springframework.data.domain.Pageable pageable);
    org.springframework.data.domain.Page<ProductReviewReportEntity> findAllByOrderByCreatedAtDesc(
            org.springframework.data.domain.Pageable pageable);
    long countByStatus(com.utewedding.app.domain.ProductReviewReportStatus status);
    java.util.List<ProductReviewReportEntity> findByReviewIdOrderByCreatedAtAsc(UUID reviewId);
}
