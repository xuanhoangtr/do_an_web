package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ProductModerationHistoryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductModerationHistoryRepository extends JpaRepository<ProductModerationHistoryEntity, UUID> {
    List<ProductModerationHistoryEntity> findByProductIdOrderByOccurredAtDescIdDesc(UUID productId);
}
