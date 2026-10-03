package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.GuestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface GuestRepository extends JpaRepository<GuestEntity, UUID> {

    List<GuestEntity> findByWeddingIdOrderByCreatedAtAsc(UUID weddingId);
    List<GuestEntity> findByWeddingIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID weddingId);

    Optional<GuestEntity> findByWeddingIdAndToken(UUID weddingId, String token);
    Optional<GuestEntity> findByWeddingIdAndTokenAndDeletedAtIsNull(UUID weddingId, String token);
    Optional<GuestEntity> findByIdAndWeddingId(UUID id, UUID weddingId);
    Optional<GuestEntity> findByIdAndWeddingIdAndDeletedAtIsNull(UUID id, UUID weddingId);
}
