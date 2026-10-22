package com.utewedding.app.repository;

import com.utewedding.app.domain.ReturnDisputeStatus;
import com.utewedding.app.domain.entity.ReturnDisputeEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnDisputeRepository extends JpaRepository<ReturnDisputeEntity, UUID> {
    java.util.List<ReturnDisputeEntity> findByReturnCaseId(UUID caseId);
    Optional<ReturnDisputeEntity> findByReturnCaseIdAndStatus(UUID caseId, ReturnDisputeStatus status);
}
