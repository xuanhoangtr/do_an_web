package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.MediaAssetEntity;
import com.utewedding.app.domain.MediaAssetKind;
import com.utewedding.app.domain.MediaAssetStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAssetEntity, UUID> {
    List<MediaAssetEntity> findByWeddingIdAndStatusOrderByDisplayOrderAscCreatedAtAsc(
            UUID weddingId, com.utewedding.app.domain.MediaAssetStatus status);

    java.util.Optional<MediaAssetEntity> findFirstByWeddingIdAndKindAndStatusOrderByCreatedAtDesc(
            UUID weddingId, MediaAssetKind kind, MediaAssetStatus status);
    java.util.Optional<MediaAssetEntity> findByIdAndWeddingId(UUID id, UUID weddingId);
}
