package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.WeddingSectionSettingEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeddingSectionSettingRepository extends JpaRepository<WeddingSectionSettingEntity, UUID> {
    Optional<WeddingSectionSettingEntity> findByWeddingId(UUID weddingId);
}
