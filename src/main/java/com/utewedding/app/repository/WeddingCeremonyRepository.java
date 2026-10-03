package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.WeddingCeremonyEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeddingCeremonyRepository extends JpaRepository<WeddingCeremonyEntity, UUID> {
    List<WeddingCeremonyEntity> findByWeddingIdOrderByDisplayOrderAsc(UUID weddingId);
    void deleteByWeddingId(UUID weddingId);
}
