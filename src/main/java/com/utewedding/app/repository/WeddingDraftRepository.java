package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.WeddingDraftEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeddingDraftRepository extends JpaRepository<WeddingDraftEntity, UUID> {
    Optional<WeddingDraftEntity> findByWeddingId(UUID weddingId);
}
