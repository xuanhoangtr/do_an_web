package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ProvinceEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProvinceRepository extends JpaRepository<ProvinceEntity, String> {
    List<ProvinceEntity> findByActiveTrueOrderByNameAsc();
    Optional<ProvinceEntity> findByCodeAndActiveTrue(String code);
    List<ProvinceEntity> findByCatalogSourceAndActiveTrueOrderByNameAsc(String catalogSource);
    List<ProvinceEntity> findByCatalogSource(String catalogSource);
    Optional<ProvinceEntity> findByCodeAndCatalogSourceAndActiveTrue(String code, String catalogSource);
    long countByCatalogSourceAndActiveTrue(String catalogSource);
}
