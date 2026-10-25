package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopPayoutRecoveryDebtEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopPayoutRecoveryDebtRepository extends JpaRepository<ShopPayoutRecoveryDebtEntity, UUID> {
    Optional<ShopPayoutRecoveryDebtEntity> findByRefundEntryId(UUID refundEntryId);
}
