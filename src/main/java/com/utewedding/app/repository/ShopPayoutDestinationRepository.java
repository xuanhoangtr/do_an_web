package com.utewedding.app.repository;

import com.utewedding.app.domain.ShopPayoutDestinationStatus;
import com.utewedding.app.domain.entity.ShopPayoutDestinationEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShopPayoutDestinationRepository extends JpaRepository<ShopPayoutDestinationEntity, UUID> {
    List<ShopPayoutDestinationEntity> findByShopIdOrderByVersionDesc(UUID shopId);
    List<ShopPayoutDestinationEntity> findTop200ByStatusOrderByCreatedAtAscId(
            ShopPayoutDestinationStatus status);
    Optional<ShopPayoutDestinationEntity> findFirstByShopIdAndStatusOrderByVersionDesc(
            UUID shopId, ShopPayoutDestinationStatus status);
    Optional<ShopPayoutDestinationEntity> findByIdAndShopId(UUID id, UUID shopId);
    @Query("select coalesce(max(d.version),0) from ShopPayoutDestinationEntity d where d.shopId=:shopId")
    long maxVersion(@Param("shopId") UUID shopId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ShopPayoutDestinationEntity d where d.id=:id")
    Optional<ShopPayoutDestinationEntity> findByIdForUpdate(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ShopPayoutDestinationEntity d where d.shopId=:shopId and d.status=:status")
    List<ShopPayoutDestinationEntity> findByShopIdAndStatusForUpdate(
            @Param("shopId") UUID shopId, @Param("status") ShopPayoutDestinationStatus status);
}
