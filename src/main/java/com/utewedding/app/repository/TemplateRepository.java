package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.TemplateEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRepository extends JpaRepository<TemplateEntity, UUID> {
    Optional<TemplateEntity> findByIdAndActiveTrue(UUID id);
    Optional<TemplateEntity> findFirstByActiveTrueAndPremiumFalseOrderByDisplayOrderAsc();
}
