package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopFinanceStateEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShopFinanceStateRepository extends JpaRepository<ShopFinanceStateEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from ShopFinanceStateEntity state where state.shopId = :shopId")
    Optional<ShopFinanceStateEntity> findByShopIdForUpdate(@Param("shopId") UUID shopId);
}
