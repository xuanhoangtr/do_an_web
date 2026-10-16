package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShippingCarrierEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShippingCarrierRepository extends JpaRepository<ShippingCarrierEntity, UUID> {
    @Query("select c from ShippingCarrierEntity c where c.coverageScope = :scope "
            + "and c.active = true and c.deletedAt is null and c.defaultForNewOrders = true")
    Optional<ShippingCarrierEntity> findActiveDefault(@Param("scope") String scope);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ShippingCarrierEntity c where c.id = :id")
    Optional<ShippingCarrierEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ShippingCarrierEntity c where c.coverageScope = :scope order by c.id")
    List<ShippingCarrierEntity> lockScope(@Param("scope") String scope);

    boolean existsByCodeIgnoreCase(String code);
}
