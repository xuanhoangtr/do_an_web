package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopOrderFinancialSnapshotEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopOrderFinancialSnapshotRepository
        extends JpaRepository<ShopOrderFinancialSnapshotEntity, UUID> {
    Optional<ShopOrderFinancialSnapshotEntity> findByOrderId(UUID orderId);
}
