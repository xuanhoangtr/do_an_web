package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.VoucherEntity;
import com.utewedding.app.domain.VoucherScope;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherRepository extends JpaRepository<VoucherEntity, UUID> {
    Optional<VoucherEntity> findByCodeIgnoreCase(String code);
    Optional<VoucherEntity> findByCodeIgnoreCaseAndScope(String code, VoucherScope scope);
    Page<VoucherEntity> findByScope(VoucherScope scope, Pageable pageable);
    Page<VoucherEntity> findByShopId(UUID shopId, Pageable pageable);
    Optional<VoucherEntity> findByIdAndShopId(UUID id, UUID shopId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VoucherEntity v where v.id = :id and v.shopId = :shopId")
    Optional<VoucherEntity> findByIdAndShopIdForUpdate(@Param("id") UUID id, @Param("shopId") UUID shopId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VoucherEntity v where upper(v.code) = upper(:code)")
    Optional<VoucherEntity> findByCodeForUpdate(@Param("code") String code);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VoucherEntity v where v.id = :id")
    Optional<VoucherEntity> findByIdForUpdate(@Param("id") UUID id);
}
