package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopInventoryAdjustmentEntity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopInventoryAdjustmentRepository
        extends JpaRepository<ShopInventoryAdjustmentEntity, UUID> {
    Page<ShopInventoryAdjustmentEntity> findByShopIdOrderByCreatedAtDescIdDesc(UUID shopId, Pageable pageable);
}
