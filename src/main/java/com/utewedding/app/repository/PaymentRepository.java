package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.PaymentEntity;
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
    List<PaymentEntity> findByOrderIdOrderByCreatedAtDesc(UUID orderId);
    List<PaymentEntity> findByOrderIdAndProviderAndAttemptIdOrderByCreatedAtAsc(
            UUID orderId, String provider, UUID attemptId);
    List<PaymentEntity> findByOrderIdInOrderByOrderIdAscCreatedAtDesc(Collection<UUID> orderIds);

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
