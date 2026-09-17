package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.CheckoutRefundAllocationEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface CheckoutRefundAllocationRepository
        extends JpaRepository<CheckoutRefundAllocationEntity, UUID> {

    List<CheckoutRefundAllocationEntity> findByCaptureAllocationIdOrderByCreatedAtAsc(UUID captureAllocationId);

    List<CheckoutRefundAllocationEntity> findByGroupIdAndOrderIdOrderByCreatedAtAsc(UUID groupId, UUID orderId);

    Optional<CheckoutRefundAllocationEntity> findByRefundTransactionId(UUID refundTransactionId);

    /**
     * Serializes refund reservations for one child against its immutable capture
     * allocation. Call this inside the transaction that reads the active sum and
     * inserts the pending provider transaction and attribution.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT id
              FROM checkout_payment_allocations
             WHERE group_id = :groupId
               AND order_id = :orderId
             FOR UPDATE
            """, nativeQuery = true)
    Optional<UUID> lockCaptureAllocationByGroupIdAndOrderId(
            @Param("groupId") UUID groupId,
            @Param("orderId") UUID orderId);

    /**
     * Sums reservations in states that have not been definitively failed.
     * Provider timeouts remain PENDING or PROCESSING until reconciliation.
     */
    default long sumActiveRefundAmountsByCaptureAllocationId(UUID captureAllocationId) {
        return sumByCaptureAllocationIdAndRefundTransactionStatusIn(
                captureAllocationId, List.of("PENDING", "PROCESSING", "SUCCEEDED"));
    }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT COALESCE(SUM(a.amount), 0)
              FROM checkout_refund_allocations a
              JOIN payment_provider_transactions t
                ON t.checkout_group_id = a.group_id
               AND t.id = a.refund_transaction_id
             WHERE a.capture_allocation_id = :captureAllocationId
               AND t.status IN (:statuses)
            """, nativeQuery = true)
    long sumByCaptureAllocationIdAndRefundTransactionStatusIn(
            @Param("captureAllocationId") UUID captureAllocationId,
            @Param("statuses") Collection<String> statuses);
}
