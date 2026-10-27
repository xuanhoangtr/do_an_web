package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.PaymentEntity;
import com.utewedding.app.domain.entity.PlatformFundReceiptAllocationEntity;
import com.utewedding.app.domain.entity.PaymentProviderTransactionEntity;
import com.utewedding.app.domain.ProviderTransactionStatus;
import com.utewedding.app.domain.ProviderTransactionType;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {
    Optional<PaymentEntity> findByProviderTransactionId(String providerTransactionId);
    Optional<PaymentEntity> findByCheckoutPaymentAllocationId(UUID checkoutPaymentAllocationId);
    List<PaymentEntity> findByOrderIdOrderByCreatedAtDesc(UUID orderId);
    List<PaymentEntity> findByOrderIdAndProviderAndAttemptIdOrderByCreatedAtAsc(
            UUID orderId, String provider, UUID attemptId);
    List<PaymentEntity> findByOrderIdInOrderByOrderIdAscCreatedAtDesc(Collection<UUID> orderIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.id = :id")
    Optional<PaymentEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            select new com.utewedding.app.repository.GatewayReceiptCandidate(
                p.id, o.id, o.orderNumber, o.shopId, p.provider, p.providerTransactionId, p.amount, p.createdAt,
                t.providerEnvironment)
            from PaymentEntity p, OrderEntity o, PaymentProviderTransactionEntity t
            where p.orderId = o.id
              and t.paymentId = p.id and t.orderId = o.id and t.provider = p.provider
              and t.transactionType = :captureType and t.status = :captureStatus
              and t.amount = p.amount and t.providerTransactionId = p.providerTransactionId
              and p.providerTransactionId is not null
              and p.provider in ('VNPAY', 'MOMO')
              and p.status in (com.utewedding.app.domain.PaymentStatus.SUCCEEDED,
                               com.utewedding.app.domain.PaymentStatus.REFUND_PENDING,
                               com.utewedding.app.domain.PaymentStatus.PARTIALLY_REFUNDED,
                               com.utewedding.app.domain.PaymentStatus.REFUNDED,
                               com.utewedding.app.domain.PaymentStatus.REFUND_FAILED)
              and exists (
                  select s.id from ShopOrderFinancialSnapshotEntity s
                  where s.orderId = o.id and s.shopId = o.shopId)
              and not exists (
                  select a.id from PlatformFundReceiptAllocationEntity a
                  where a.sourceKind = com.utewedding.app.domain.PlatformFundReceiptSourceKind.ONLINE_PAYMENT
                    and a.sourceReference = concat(concat(p.provider, ':'), p.providerTransactionId))
            order by p.createdAt desc, p.id
            """,
            countQuery = """
            select count(p.id)
            from PaymentEntity p, OrderEntity o
            where p.orderId = o.id
              and p.providerTransactionId is not null
              and p.provider in ('VNPAY', 'MOMO')
              and p.status in (com.utewedding.app.domain.PaymentStatus.SUCCEEDED,
                               com.utewedding.app.domain.PaymentStatus.REFUND_PENDING,
                               com.utewedding.app.domain.PaymentStatus.PARTIALLY_REFUNDED,
                               com.utewedding.app.domain.PaymentStatus.REFUNDED,
                               com.utewedding.app.domain.PaymentStatus.REFUND_FAILED)
              and exists (
                  select t.id from PaymentProviderTransactionEntity t
                  where t.paymentId = p.id and t.provider = p.provider
                    and t.transactionType = :captureType and t.status = :captureStatus
                    and t.amount = p.amount and t.providerTransactionId = p.providerTransactionId)
              and exists (
                  select s.id from ShopOrderFinancialSnapshotEntity s
                  where s.orderId = o.id and s.shopId = o.shopId)
              and not exists (
                  select a.id from PlatformFundReceiptAllocationEntity a
                  where a.sourceKind = com.utewedding.app.domain.PlatformFundReceiptSourceKind.ONLINE_PAYMENT
                    and a.sourceReference = concat(concat(p.provider, ':'), p.providerTransactionId))
            """)
    Page<GatewayReceiptCandidate> findGatewayReceiptCandidates(
            @Param("captureType") ProviderTransactionType captureType,
            @Param("captureStatus") ProviderTransactionStatus captureStatus, Pageable pageable);

    @Query(value = """
            select new com.utewedding.app.repository.AdminPaymentRow(
                p.id, o.id, o.orderNumber, o.createdAt, o.paymentMethod, p.provider,
                p.amount, p.providerTransactionId, p.createdAt, p.status, o.status)
            from PaymentEntity p, OrderEntity o
            where p.orderId = o.id
              and (cast(:q as string) is null or lower(o.orderNumber) like lower(concat('%', cast(:q as string), '%'))
                   or lower(coalesce(p.providerTransactionId, '')) like lower(concat('%', cast(:q as string), '%')))
              and (cast(:method as string) is null or o.paymentMethod = cast(:method as string))
              and (:paymentStatus is null or p.status = :paymentStatus)
              and (:orderStatus is null or o.status = :orderStatus)
            order by p.createdAt desc
            """,
            countQuery = """
            select count(p.id) from PaymentEntity p, OrderEntity o
            where p.orderId = o.id
              and (cast(:q as string) is null or lower(o.orderNumber) like lower(concat('%', cast(:q as string), '%'))
                   or lower(coalesce(p.providerTransactionId, '')) like lower(concat('%', cast(:q as string), '%')))
              and (cast(:method as string) is null or o.paymentMethod = cast(:method as string))
              and (:paymentStatus is null or p.status = :paymentStatus)
              and (:orderStatus is null or o.status = :orderStatus)
            """)
    Page<AdminPaymentRow> searchAdmin(@Param("q") String query,
                                      @Param("method") String method,
                                      @Param("paymentStatus") PaymentStatus paymentStatus,
                                      @Param("orderStatus") OrderStatus orderStatus,
                                      Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.orderId = :orderId order by p.id asc")
    List<PaymentEntity> findByOrderIdForUpdateOrderByIdAsc(@Param("orderId") UUID orderId);
}
