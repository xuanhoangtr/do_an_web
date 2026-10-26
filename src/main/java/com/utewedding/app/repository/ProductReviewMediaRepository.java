package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ProductReviewMediaEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductReviewMediaRepository extends JpaRepository<ProductReviewMediaEntity, UUID> {
    List<ProductReviewMediaEntity> findByReviewIdOrderByCreatedAtAsc(UUID reviewId);
    List<ProductReviewMediaEntity> findByReviewIdInOrderByCreatedAtAsc(Collection<UUID> reviewIds);
    java.util.Optional<ProductReviewMediaEntity> findByIdAndReviewId(UUID id, UUID reviewId);
    long countByReviewId(UUID reviewId);
}
