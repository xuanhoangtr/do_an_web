package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.AdministrativeAddressCatalogStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface AdministrativeAddressCatalogStateRepository
        extends JpaRepository<AdministrativeAddressCatalogStateEntity, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AdministrativeAddressCatalogStateEntity s where s.singletonId = :id")
    Optional<AdministrativeAddressCatalogStateEntity> findByIdForUpdate(@Param("id") Short id);
}
