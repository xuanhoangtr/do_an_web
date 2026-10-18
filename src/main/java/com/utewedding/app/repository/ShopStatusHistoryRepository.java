package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopStatusHistoryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopStatusHistoryRepository extends JpaRepository<ShopStatusHistoryEntity, UUID> {
    List<ShopStatusHistoryEntity> findByShopIdOrderByOccurredAtDescIdDesc(UUID shopId);
}
