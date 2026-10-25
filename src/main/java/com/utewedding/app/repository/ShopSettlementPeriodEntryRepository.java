package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopSettlementPeriodEntryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopSettlementPeriodEntryRepository
        extends JpaRepository<ShopSettlementPeriodEntryEntity, UUID> {
    List<ShopSettlementPeriodEntryEntity> findByPeriodIdOrderByCreatedAtAscIdAsc(UUID periodId);
}
