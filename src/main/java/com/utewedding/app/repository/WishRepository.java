package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.WishEntity;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WishRepository extends JpaRepository<WishEntity, UUID> {
    Optional<WishEntity> findByIdAndWeddingId(UUID id, UUID weddingId);
    List<WishEntity> findByWeddingIdAndStatusOrderByCreatedAtDesc(
            UUID weddingId, com.utewedding.app.domain.WishStatus status);
    List<WishEntity> findByWeddingIdOrderByCreatedAtDesc(UUID weddingId);
    Page<WishEntity> findByWeddingIdOrderByCreatedAtDesc(UUID weddingId, Pageable pageable);
    Page<WishEntity> findByWeddingIdAndStatusOrderByCreatedAtDesc(
            UUID weddingId, com.utewedding.app.domain.WishStatus status, Pageable pageable);
    long countByStatus(com.utewedding.app.domain.WishStatus status);
}
