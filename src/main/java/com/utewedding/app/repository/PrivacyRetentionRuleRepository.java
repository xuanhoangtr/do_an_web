package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.PrivacyRetentionRuleEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivacyRetentionRuleRepository extends JpaRepository<PrivacyRetentionRuleEntity, UUID> {
    List<PrivacyRetentionRuleEntity> findByActiveTrueOrderByCategoryKeyAsc();
}
