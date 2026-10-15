package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.CheckoutPaymentAllocationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CheckoutPaymentAllocationRepository
        extends JpaRepository<CheckoutPaymentAllocationEntity, UUID> {

    List<CheckoutPaymentAllocationEntity> findByGroupIdOrderByCreatedAtAsc(UUID groupId);

    List<CheckoutPaymentAllocationEntity> findByCaptureTransactionIdOrderByOrderIdAsc(UUID captureTransactionId);

    Optional<CheckoutPaymentAllocationEntity> findByGroupIdAndOrderId(UUID groupId, UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CheckoutPaymentAllocationEntity a where a.groupId = :groupId order by a.orderId")
    List<CheckoutPaymentAllocationEntity> findByGroupIdForUpdateOrderByOrderIdAsc(@Param("groupId") UUID groupId);
}
