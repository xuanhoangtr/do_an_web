package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.WardEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WardRepository extends JpaRepository<WardEntity, String> {
    List<WardEntity> findByProvinceCodeAndActiveTrueOrderByNameAsc(String provinceCode);
    Optional<WardEntity> findByCodeAndProvinceCodeAndActiveTrue(String code, String provinceCode);
    List<WardEntity> findByCatalogSourceAndProvinceCodeAndActiveTrueOrderByNameAsc(
            String catalogSource, String provinceCode);
    List<WardEntity> findByCatalogSource(String catalogSource);
    Optional<WardEntity> findByCodeAndProvinceCodeAndCatalogSourceAndActiveTrue(
            String code, String provinceCode, String catalogSource);
    long countByCatalogSourceAndActiveTrue(String catalogSource);
}
