package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShopCommissionPolicyEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopCommissionPolicyRepository extends JpaRepository<ShopCommissionPolicyEntity, Long> {
    Optional<ShopCommissionPolicyEntity> findTopByEffectiveAtLessThanEqualOrderByEffectiveAtDescPolicyVersionDesc(
            Instant effectiveAt);
    Optional<ShopCommissionPolicyEntity> findTopByOrderByPolicyVersionDesc();
    Optional<ShopCommissionPolicyEntity> findTopByOrderByEffectiveAtDesc();
}
