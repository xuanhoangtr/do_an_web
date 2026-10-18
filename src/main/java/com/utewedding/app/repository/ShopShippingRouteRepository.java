package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopShippingRouteEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShopShippingRouteRepository extends JpaRepository<ShopShippingRouteEntity, UUID> {
    Optional<ShopShippingRouteEntity> findByBindingIdAndShopIdAndCatalogSourceAndProvinceCodeAndWardCodeAndStatusAndActiveTrue(
            UUID bindingId, UUID shopId, String catalogSource, String provinceCode, String wardCode, String status);
    List<ShopShippingRouteEntity> findByBindingIdAndShopIdOrderByProvinceNameAscWardNameAsc(
            UUID bindingId, UUID shopId);
    Optional<ShopShippingRouteEntity> findByIdAndBindingIdAndShopId(UUID id, UUID bindingId, UUID shopId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ShopShippingRouteEntity r where r.id = :id and r.bindingId = :bindingId and r.shopId = :shopId")
    Optional<ShopShippingRouteEntity> findForUpdate(@Param("id") UUID id, @Param("bindingId") UUID bindingId,
                                                     @Param("shopId") UUID shopId);
    boolean existsByBindingIdAndShopIdAndStatus(UUID bindingId, UUID shopId, String status);
}
