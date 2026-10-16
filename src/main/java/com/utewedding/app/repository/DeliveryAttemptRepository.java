package com.utewedding.app.repository;

import com.utewedding.app.domain.DeliveryAttemptOutcome;
import com.utewedding.app.domain.entity.DeliveryAttemptEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttemptEntity, UUID> {
    List<DeliveryAttemptEntity> findByFulfillmentIdOrderByAttemptedAtAsc(UUID fulfillmentId);
    List<DeliveryAttemptEntity> findByFulfillmentIdInOrderByAttemptedAtAsc(Collection<UUID> fulfillmentIds);
    long countByShipperId(UUID shipperId);
    long countByShipperIdAndOutcome(UUID shipperId, DeliveryAttemptOutcome outcome);
}
