package com.utewedding.app.repository;

import com.utewedding.app.domain.ShopAddressKind;
import com.utewedding.app.domain.entity.ShopAddressHeadEntity;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShopAddressHeadRepository extends JpaRepository<ShopAddressHeadEntity, UUID> {
    Optional<ShopAddressHeadEntity> findByShopIdAndAddressKind(UUID shopId, ShopAddressKind addressKind);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from ShopAddressHeadEntity h where h.shopId = :shopId and h.addressKind = :kind")
    Optional<ShopAddressHeadEntity> findForUpdate(@Param("shopId") UUID shopId,
                                                 @Param("kind") ShopAddressKind addressKind);
}
