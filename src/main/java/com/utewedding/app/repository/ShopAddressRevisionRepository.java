package com.utewedding.app.repository;

import com.utewedding.app.domain.ShopAddressKind;
import com.utewedding.app.domain.entity.ShopAddressRevisionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopAddressRevisionRepository extends JpaRepository<ShopAddressRevisionEntity, UUID> {
    List<ShopAddressRevisionEntity> findByShopIdAndAddressKindOrderByRevisionDesc(UUID shopId, ShopAddressKind kind);
    Optional<ShopAddressRevisionEntity> findByIdAndShopIdAndAddressKind(UUID id, UUID shopId, ShopAddressKind kind);
}
