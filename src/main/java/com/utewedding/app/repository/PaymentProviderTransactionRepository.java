package com.utewedding.app.repository;

import com.utewedding.app.domain.ProviderTransactionType;
import com.utewedding.app.domain.ProviderTransactionStatus;
import com.utewedding.app.domain.entity.PaymentProviderTransactionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface PaymentProviderTransactionRepository extends JpaRepository<PaymentProviderTransactionEntity, UUID> {
    Optional<PaymentProviderTransactionEntity> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentProviderTransactionEntity> findByProviderAndProviderTransactionIdAndTransactionType(
            String provider, String providerTransactionId, ProviderTransactionType transactionType);
    List<PaymentProviderTransactionEntity> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
    List<PaymentProviderTransactionEntity> findByCheckoutGroupIdOrderByCreatedAtAsc(UUID checkoutGroupId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentProviderTransactionEntity t where t.id = :id")
    Optional<PaymentProviderTransactionEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            select new com.utewedding.app.repository.GroupedGatewayReceiptCandidate(
                t.id, g.id, g.groupNumber, t.provider, t.providerTransactionId,
                t.amount, t.createdAt, t.providerEnvironment,
                (select count(a.id) from CheckoutPaymentAllocationEntity a where a.captureTransactionId = t.id))
            from PaymentProviderTransactionEntity t, CheckoutGroupEntity g
            where t.checkoutGroupId = g.id
              and t.orderId is null and t.paymentId is null
              and t.transactionType = :captureType and t.status = :captureStatus
              and t.providerTransactionId is not null
              and t.provider in ('VNPAY', 'MOMO')
              and t.amount = g.totalAmount and t.currency = g.currency
              and exists (select a.id from CheckoutPaymentAllocationEntity a
                          where a.captureTransactionId = t.id and a.groupId = g.id)
              and not exists (select r.id from PlatformFundReceiptAllocationEntity r
                              where r.sourceKind = com.utewedding.app.domain.PlatformFundReceiptSourceKind.ONLINE_PAYMENT
                                and r.sourceReference = concat(concat(t.provider, ':'), t.providerTransactionId))
            order by t.createdAt desc, t.id
            """,
            countQuery = """
            select count(t.id) from PaymentProviderTransactionEntity t, CheckoutGroupEntity g
            where t.checkoutGroupId = g.id
              and t.orderId is null and t.paymentId is null
              and t.transactionType = :captureType and t.status = :captureStatus
              and t.providerTransactionId is not null
              and t.provider in ('VNPAY', 'MOMO')
              and t.amount = g.totalAmount and t.currency = g.currency
              and exists (select a.id from CheckoutPaymentAllocationEntity a
                          where a.captureTransactionId = t.id and a.groupId = g.id)
              and not exists (select r.id from PlatformFundReceiptAllocationEntity r
                              where r.sourceKind = com.utewedding.app.domain.PlatformFundReceiptSourceKind.ONLINE_PAYMENT
                                and r.sourceReference = concat(concat(t.provider, ':'), t.providerTransactionId))
            """)
    org.springframework.data.domain.Page<GroupedGatewayReceiptCandidate> findGroupedGatewayReceiptCandidates(
            @Param("captureType") ProviderTransactionType captureType,
            @Param("captureStatus") ProviderTransactionStatus captureStatus,
            org.springframework.data.domain.Pageable pageable);
    List<PaymentProviderTransactionEntity> findByPaymentIdAndTransactionTypeAndStatusOrderByCreatedAtAsc(
            UUID paymentId, ProviderTransactionType transactionType, ProviderTransactionStatus status);
    List<PaymentProviderTransactionEntity> findByReturnResolutionIdOrderByCreatedAtAsc(UUID returnResolutionId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentProviderTransactionEntity t where t.idempotencyKey = :key")
    Optional<PaymentProviderTransactionEntity> findByIdempotencyKeyForUpdate(@Param("key") String key);
    @Query(value = "select id from payment_provider_transactions where transaction_type = 'REFUND' "
            + "and (status = 'PENDING' or (status = 'PROCESSING' and processed_at <= :staleBefore)) "
            + "order by created_at, id limit :limit", nativeQuery = true)
    List<UUID> findRefundIntentsDue(@Param("staleBefore") Instant staleBefore, @Param("limit") int limit);
}
