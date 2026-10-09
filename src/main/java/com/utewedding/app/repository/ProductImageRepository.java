package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ProductImageEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImageEntity, UUID> {
    List<ProductImageEntity> findByProductIdInOrderByProductIdAscDisplayOrderAscCreatedAtAsc(
            Collection<UUID> productIds);

    Optional<ProductImageEntity> findFirstByProductIdAndPrimaryImageTrueOrderByDisplayOrderAscCreatedAtAsc(
            UUID productId);

    void deleteByProductIdAndPrimaryImageTrue(UUID productId);
}
