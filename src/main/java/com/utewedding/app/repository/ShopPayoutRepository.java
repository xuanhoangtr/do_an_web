package com.utewedding.app.repository;

import com.utewedding.app.domain.ShopPayoutStatus;
import com.utewedding.app.domain.entity.ShopPayoutEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ShopPayoutRepository extends JpaRepository<ShopPayoutEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ShopPayoutEntity p where p.id=:id")
    Optional<ShopPayoutEntity> findByIdForUpdate(@Param("id") UUID id);
    Optional<ShopPayoutEntity> findByIdAndShopId(UUID id, UUID shopId);
    Optional<ShopPayoutEntity> findBySettlementPeriodIdAndStatusIn(UUID periodId, List<ShopPayoutStatus> statuses);
    List<ShopPayoutEntity> findTop100ByShopIdOrderByCreatedAtDesc(UUID shopId);
    List<ShopPayoutEntity> findTop200ByStatusInOrderByCreatedAtAsc(List<ShopPayoutStatus> statuses);
    boolean existsByProviderCodeAndProviderReference(String providerCode, String providerReference);
    boolean existsByProofMediaId(UUID proofMediaId);

    /** Claims the first evidence upload for a payout while it is already in processing. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE shop_payouts
            SET proof_media_id = :mediaId, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :payoutId AND status = 'PROCESSING' AND proof_media_id IS NULL
            """, nativeQuery = true)
    int attachProofToProcessingPayout(@Param("payoutId") UUID payoutId, @Param("mediaId") UUID mediaId);
}
