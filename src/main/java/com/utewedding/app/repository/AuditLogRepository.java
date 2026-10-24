package com.utewedding.app.repository;

import com.utewedding.app.domain.AuditAction;
import com.utewedding.app.domain.entity.AuditLogEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID>, JpaSpecificationExecutor<AuditLogEntity> {
    List<AuditLogEntity> findByTargetTypeAndTargetIdAndActionOrderByCreatedAtAsc(
            String targetType, UUID targetId, AuditAction action);
    List<AuditLogEntity> findByTargetTypeAndTargetIdAndActionInOrderByCreatedAtAsc(
            String targetType, UUID targetId, Collection<AuditAction> actions);
    List<AuditLogEntity> findByTargetTypeAndTargetIdInAndActionOrderByCreatedAtAsc(
            String targetType, Collection<UUID> targetIds, AuditAction action);
    List<AuditLogEntity> findByTargetTypeAndTargetIdInAndActionInOrderByCreatedAtAsc(
            String targetType, Collection<UUID> targetIds, Collection<AuditAction> actions);

    Page<AuditLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<AuditLogEntity> findByActorIdOrderByCreatedAtDesc(UUID actorId, Pageable pageable);
    List<AuditLogEntity> findAllByActorIdOrderByCreatedAtDesc(UUID actorId);
    Page<AuditLogEntity> findByActionOrderByCreatedAtDesc(AuditAction action, Pageable pageable);
    Page<AuditLogEntity> findByTargetTypeOrderByCreatedAtDesc(String targetType, Pageable pageable);
    Page<AuditLogEntity> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            String targetType, UUID targetId, Pageable pageable);
    Page<AuditLogEntity> findByTargetTypeAndTargetReferenceOrderByCreatedAtDesc(
            String targetType, String targetReference, Pageable pageable);
    Page<AuditLogEntity> findByCreatedAtBetweenOrderByCreatedAtDesc(
            Instant from, Instant to, Pageable pageable);
}
