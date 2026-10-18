package com.utewedding.app.repository;

import com.utewedding.app.domain.ShopApplicationStatus;
import com.utewedding.app.domain.entity.ShopApplicationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopApplicationRepository extends JpaRepository<ShopApplicationEntity, UUID> {
    List<ShopApplicationEntity> findByShopIdOrderByAttemptNumberDesc(UUID shopId);

    Optional<ShopApplicationEntity> findByShopIdAndAttemptNumber(UUID shopId, int attemptNumber);

    Optional<ShopApplicationEntity> findTopByShopIdOrderByAttemptNumberDesc(UUID shopId);

    List<ShopApplicationEntity> findByStatusOrderBySubmittedAtAsc(ShopApplicationStatus status);
}
