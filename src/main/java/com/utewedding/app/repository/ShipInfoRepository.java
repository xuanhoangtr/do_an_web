package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShipInfoEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipInfoRepository extends JpaRepository<ShipInfoEntity, UUID> {
    List<ShipInfoEntity> findByUserIdOrderByDefaultAddressDescCreatedAtDesc(UUID userId);
    List<ShipInfoEntity> findTop10ByUserIdOrderByDefaultAddressDescCreatedAtDesc(UUID userId);
    long countByUserId(UUID userId);
    Optional<ShipInfoEntity> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("update ShipInfoEntity s set s.defaultAddress = false where s.userId = :userId")
    int clearDefaultForUser(@Param("userId") UUID userId);

    long deleteByUserId(UUID userId);
}
