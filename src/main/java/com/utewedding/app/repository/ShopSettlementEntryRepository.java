package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopSettlementEntryEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopSettlementEntryRepository extends JpaRepository<ShopSettlementEntryEntity, UUID> {
    Optional<ShopSettlementEntryEntity> findBySourceIdentity(String sourceIdentity);

    Optional<ShopSettlementEntryEntity> findFirstByShopIdAndOrderIdAndEntryType(
            UUID shopId, UUID orderId, ShopSettlementEntryEntity.Type entryType);
}
