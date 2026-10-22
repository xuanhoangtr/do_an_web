package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ReturnCaseItemEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnCaseItemRepository extends JpaRepository<ReturnCaseItemEntity, UUID> {
    List<ReturnCaseItemEntity> findByReturnCaseIdOrderByIdAsc(UUID returnCaseId);
}