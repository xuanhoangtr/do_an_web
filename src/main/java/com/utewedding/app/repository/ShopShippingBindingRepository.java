package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopShippingBindingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShopShippingBindingRepository extends JpaRepository<ShopShippingBindingEntity, UUID> {
    List<ShopShippingBindingEntity> findByShopIdOrderByRevisionDesc(UUID shopId);
    Optional<ShopShippingBindingEntity> findByIdAndShopId(UUID id, UUID shopId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ShopShippingBindingEntity b where b.id = :id and b.shopId = :shopId")
    Optional<ShopShippingBindingEntity> findByIdAndShopIdForUpdate(@Param("id") UUID id,
                                                                  @Param("shopId") UUID shopId);
    long countByShopId(UUID shopId);
}
