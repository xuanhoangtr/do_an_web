package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ReturnResolutionEntity;
import com.utewedding.app.domain.ReturnResolutionStatus;
import com.utewedding.app.domain.ReturnResolutionType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReturnResolutionRepository extends JpaRepository<ReturnResolutionEntity, UUID> {
    boolean existsByManualReference(String reference);
    Optional<ReturnResolutionEntity> findByManualReference(String reference);
    Optional<ReturnResolutionEntity> findByIdempotencyKey(String key);
    Optional<ReturnResolutionEntity> findByReturnCaseId(UUID caseId);
    List<ReturnResolutionEntity> findByTypeAndStatusOrderByCreatedAtAsc(
            ReturnResolutionType type, ReturnResolutionStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReturnResolutionEntity r where r.id = :id")
    Optional<ReturnResolutionEntity> findByIdForUpdate(@Param("id") UUID id);
}
