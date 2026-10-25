package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopPayoutOperationEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShopPayoutOperationRepository extends JpaRepository<ShopPayoutOperationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ShopPayoutOperationEntity o where o.actorId=:actorId and o.idempotencyKey=:key")
    Optional<ShopPayoutOperationEntity> findByActorIdAndIdempotencyKeyForUpdate(
            @Param("actorId") UUID actorId, @Param("key") String key);
}
