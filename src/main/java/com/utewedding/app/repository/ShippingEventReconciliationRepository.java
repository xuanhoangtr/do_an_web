package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ShippingEventReconciliationEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShippingEventReconciliationRepository
        extends JpaRepository<ShippingEventReconciliationEntity, UUID> {
    boolean existsByShippingEventId(UUID shippingEventId);
}
