package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ReturnCaseHistoryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnCaseHistoryRepository extends JpaRepository<ReturnCaseHistoryEntity, UUID> {
    List<ReturnCaseHistoryEntity> findByReturnCaseIdOrderByCreatedAtAsc(UUID returnCaseId);
}