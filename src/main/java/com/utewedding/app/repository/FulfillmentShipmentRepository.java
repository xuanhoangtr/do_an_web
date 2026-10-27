package com.utewedding.app.repository;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.entity.FulfillmentShipmentEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FulfillmentShipmentRepository extends JpaRepository<FulfillmentShipmentEntity, UUID> {
    Optional<FulfillmentShipmentEntity> findByProviderCodeAndProviderShipmentId(String code, String id);
    List<FulfillmentShipmentEntity> findAllByProviderCodeAndProviderShipmentId(String code, String id);
    Optional<FulfillmentShipmentEntity> findFirstByFulfillmentIdOrderByCreatedAtDesc(UUID id);
    Optional<FulfillmentShipmentEntity> findFirstByFulfillmentIdAndProviderCodeOrderByCreatedAtDesc(
            UUID id, String providerCode);
    List<FulfillmentShipmentEntity> findByFulfillmentIdIn(Collection<UUID> ids);
    List<FulfillmentShipmentEntity> findByFulfillmentIdAndStatusNotInOrderByCreatedAtDesc(
            UUID fulfillmentId, Collection<FulfillmentStatus> excludedStatuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FulfillmentShipmentEntity s where s.id=:id")
    Optional<FulfillmentShipmentEntity> findByIdForUpdate(@Param("id") UUID id);
}
