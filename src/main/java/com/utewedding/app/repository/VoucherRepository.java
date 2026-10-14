package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.VoucherEntity;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherRepository extends JpaRepository<VoucherEntity, UUID> {
    Optional<VoucherEntity> findByCodeIgnoreCase(String code);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VoucherEntity v where upper(v.code) = upper(:code)")
    Optional<VoucherEntity> findByCodeForUpdate(@Param("code") String code);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VoucherEntity v where v.id = :id")
    Optional<VoucherEntity> findByIdForUpdate(@Param("id") UUID id);
}
