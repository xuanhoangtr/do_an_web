package com.utewedding.app.repository;

import com.utewedding.app.domain.ReturnShipmentDirection;
import com.utewedding.app.domain.entity.ReturnShipmentEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnShipmentRepository extends JpaRepository<ReturnShipmentEntity, UUID> {
    java.util.List<ReturnShipmentEntity> findByReturnCaseId(UUID caseId);
    Optional<ReturnShipmentEntity> findByReturnCaseIdAndDirection(UUID caseId, ReturnShipmentDirection direction);
}