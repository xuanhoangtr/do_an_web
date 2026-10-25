package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopSettlementPeriodEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShopSettlementPeriodRepository extends JpaRepository<ShopSettlementPeriodEntity, UUID> {
    Optional<ShopSettlementPeriodEntity> findByShopIdAndPeriodStartAt(UUID shopId, Instant periodStartAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select period from ShopSettlementPeriodEntity period where period.id = :periodId")
    Optional<ShopSettlementPeriodEntity> findByIdForUpdate(@Param("periodId") UUID periodId);
}
