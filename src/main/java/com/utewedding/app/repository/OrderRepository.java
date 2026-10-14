package com.utewedding.app.repository;

import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.PaymentStatus;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import jakarta.persistence.LockModeType;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
    String HISTORY_FULFILLMENT_CTE = """
            with fulfillment_summary as (
                select f.order_id,
                       case
                           when count(f.id) = 0 then 'NOT_READY'
                           when sum(case when f.status <> 'CANCELLED' then 1 else 0 end) = 0 then 'CANCELLED'
                           when sum(case when f.status = 'DELIVERED' then 1 else 0 end) > 0
                                and sum(case when f.status = 'RETURNED' then 1 else 0 end) > 0
                                and sum(case when f.status not in ('CANCELLED', 'DELIVERED', 'RETURNED')
                                             then 1 else 0 end) = 0 then 'PARTIALLY_DELIVERED_RETURNED'
                           when sum(case when f.status = 'RETURNING' then 1 else 0 end) > 0 then 'RETURNING'
                           when sum(case when f.status <> 'CANCELLED' then 1 else 0 end)
                                = sum(case when f.status = 'RETURNED' then 1 else 0 end) then 'RETURNED'
                           when sum(case when f.status = 'DELIVERY_FAILED' then 1 else 0 end) > 0
                                and sum(case when f.status in ('IN_TRANSIT', 'RETRY_DELIVERY', 'SHIPPING')
                                             then 1 else 0 end) = 0 then 'DELIVERY_FAILED'
                           when sum(case when f.status in ('IN_TRANSIT', 'RETRY_DELIVERY', 'SHIPPING')
                                         then 1 else 0 end) > 0 then 'IN_TRANSIT'
                           when sum(case when f.status = 'PICKED_UP' then 1 else 0 end) > 0 then 'PICKED_UP'
                           when sum(case when f.status = 'PICKUP_PENDING' then 1 else 0 end) > 0 then 'PICKUP_PENDING'
                           when sum(case when f.status <> 'CANCELLED' then 1 else 0 end)
                                = sum(case when f.status = 'DELIVERED' then 1 else 0 end) then 'DELIVERED'
                           when sum(case when f.status = 'DELIVERED' then 1 else 0 end) > 0 then 'PARTIALLY_DELIVERED'
                           when sum(case when f.status = 'PROCESSING' then 1 else 0 end) > 0 then 'PROCESSING'
                           when sum(case when f.status <> 'CANCELLED' then 1 else 0 end)
                                = sum(case when f.status = 'CONFIRMED' then 1 else 0 end) then 'CONFIRMED'
                           else 'NOT_READY'
                       end as overall_status
                from order_fulfillments f
                join orders owner_order on owner_order.id = f.order_id
                where owner_order.user_id = :userId
                group by f.order_id
            )
            """;
    Optional<OrderEntity> findByOrderNumber(String orderNumber);
    Optional<OrderEntity> findByOrderNumberAndUserId(String orderNumber, UUID userId);
    Optional<OrderEntity> findByTrackingAccessCode(UUID trackingAccessCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.orderNumber = :orderNumber")
    Optional<OrderEntity> findByOrderNumberForUpdate(@Param("orderNumber") String orderNumber);

    Optional<OrderEntity> findByIdAndUserId(UUID id, UUID userId);
    Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);
    List<OrderEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
    long countByUserId(UUID userId);
    long countByUserIdAndCreatedAtGreaterThanEqual(UUID userId, Instant from);
    long countByUserIdAndStatusAndCreatedAtGreaterThanEqual(UUID userId, OrderStatus status, Instant from);
    long countByUserIdAndPaymentStatusAndCreatedAtGreaterThanEqual(UUID userId, PaymentStatus status, Instant from);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.userId = :userId order by o.id")
    List<OrderEntity> findByUserIdForUpdateOrderByIdAsc(@Param("userId") UUID userId);
    Page<OrderEntity> findByUserId(UUID userId, Pageable pageable);
    List<OrderEntity> findByStatusAndExpiresAtBefore(OrderStatus status, Instant expiresAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.status = com.utewedding.app.domain.OrderStatus.PENDING "
            + "and o.expiresAt is not null and o.expiresAt <= :now order by o.id")
    List<OrderEntity> findPendingExpiredForUpdate(@Param("now") Instant now);

    Page<OrderEntity> findByIdIn(Collection<UUID> orderIds, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.id = :id")
    Optional<OrderEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Compare-and-set so a concurrent transition cannot overwrite a committed status. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE orders SET status = :target, updated_at = :now "
            + "WHERE id = :id AND status = :expected", nativeQuery = true)
    int compareAndSetStatus(@Param("id") UUID id, @Param("expected") String expected,
                            @Param("target") String target, @Param("now") Instant now);

    Page<OrderEntity> findByStatusInOrderByCreatedAtDesc(Collection<OrderStatus> statuses, Pageable pageable);
    long countByStatus(OrderStatus status);

    @Query("select coalesce(sum(o.totalAmount), 0) from OrderEntity o where o.status = :status")
    long sumTotalAmountByStatus(@Param("status") OrderStatus status);

    @Query("select coalesce(sum(o.totalAmount), 0) from OrderEntity o where o.paymentStatus = :status")
    long sumTotalAmountByPaymentStatus(@Param("status") PaymentStatus status);

    /**
     * Legacy rows may not have paid_at. The fallback is intentionally limited
     * to rows where paid_at is null so a late payment is never counted twice.
     */
    @Query("select o from OrderEntity o where o.status = :status "
            + "and ((o.paidAt is not null and o.paidAt >= :from and o.paidAt < :to) "
            + "or (o.paidAt is null and o.createdAt >= :from and o.createdAt < :to))")
    List<OrderEntity> findRevenueOrders(@Param("status") OrderStatus status,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to);

    @Query("select o from OrderEntity o where o.paymentStatus in ("
            + "com.utewedding.app.domain.PaymentStatus.SUCCEEDED, "
            + "com.utewedding.app.domain.PaymentStatus.PARTIALLY_REFUNDED) "
            + "and ((o.paidAt is not null and o.paidAt >= :from and o.paidAt < :to) "
            + "or (o.paidAt is null and o.createdAt >= :from and o.createdAt < :to))")
    List<OrderEntity> findSuccessfulRevenueOrders(@Param("from") Instant from, @Param("to") Instant to);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.id = :id and o.userId = :userId")
    Optional<OrderEntity> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query(value = HISTORY_FULFILLMENT_CTE + """
            select o.*
            from orders o
            left join fulfillment_summary fs on fs.order_id = o.id
            where o.user_id = :userId
              and (cast(:orderNumber as varchar) is null or lower(o.order_number) like lower(concat('%', cast(:orderNumber as varchar), '%')))
              and (cast(:status as varchar) is null or o.status = cast(:status as varchar))
              and (cast(:paymentStatus as varchar) is null or o.payment_status = cast(:paymentStatus as varchar))
              and (cast(:from as timestamp with time zone) is null or o.created_at >= cast(:from as timestamp with time zone))
              and (cast(:to as timestamp with time zone) is null or o.created_at < cast(:to as timestamp with time zone))
              and (cast(:fulfillmentStatus as varchar) is null or exists (
                    select 1 from order_fulfillments f
                    where f.order_id = o.id and f.status = cast(:fulfillmentStatus as varchar)))
              and (cast(:historyGroup as varchar) is null or
                   case
                       when o.status = 'PENDING' then 'PENDING_PAYMENT'
                       when o.status in ('CANCELLED', 'EXPIRED', 'REFUNDED')
                            or fs.overall_status in ('CANCELLED', 'RETURNING', 'RETURNED', 'PARTIALLY_DELIVERED_RETURNED')
                            then 'CANCELLED_RETURNED'
                       when fs.overall_status in ('CONFIRMED', 'PROCESSING', 'PICKUP_PENDING') then 'PREPARING'
                       when fs.overall_status in ('PICKED_UP', 'IN_TRANSIT') then 'SHIPPING'
                       when fs.overall_status in ('DELIVERED', 'PARTIALLY_DELIVERED') then 'DELIVERED'
                       else 'ALL'
                   end = cast(:historyGroup as varchar))
            order by o.created_at desc, o.id desc
            """,
            countQuery = HISTORY_FULFILLMENT_CTE + """
            select count(o.id)
            from orders o
            left join fulfillment_summary fs on fs.order_id = o.id
            where o.user_id = :userId
              and (cast(:orderNumber as varchar) is null or lower(o.order_number) like lower(concat('%', cast(:orderNumber as varchar), '%')))
              and (cast(:status as varchar) is null or o.status = cast(:status as varchar))
              and (cast(:paymentStatus as varchar) is null or o.payment_status = cast(:paymentStatus as varchar))
              and (cast(:from as timestamp with time zone) is null or o.created_at >= cast(:from as timestamp with time zone))
              and (cast(:to as timestamp with time zone) is null or o.created_at < cast(:to as timestamp with time zone))
              and (cast(:fulfillmentStatus as varchar) is null or exists (
                    select 1 from order_fulfillments f
                    where f.order_id = o.id and f.status = cast(:fulfillmentStatus as varchar)))
              and (cast(:historyGroup as varchar) is null or
                   case
                       when o.status = 'PENDING' then 'PENDING_PAYMENT'
                       when o.status in ('CANCELLED', 'EXPIRED', 'REFUNDED')
                            or fs.overall_status in ('CANCELLED', 'RETURNING', 'RETURNED', 'PARTIALLY_DELIVERED_RETURNED')
                            then 'CANCELLED_RETURNED'
                       when fs.overall_status in ('CONFIRMED', 'PROCESSING', 'PICKUP_PENDING') then 'PREPARING'
                       when fs.overall_status in ('PICKED_UP', 'IN_TRANSIT') then 'SHIPPING'
                       when fs.overall_status in ('DELIVERED', 'PARTIALLY_DELIVERED') then 'DELIVERED'
                       else 'ALL'
                   end = cast(:historyGroup as varchar))
            """, nativeQuery = true)
    Page<OrderEntity> searchForCustomer(@Param("userId") UUID userId,
                                        @Param("orderNumber") String orderNumber,
                                        @Param("status") String status,
                                        @Param("paymentStatus") String paymentStatus,
                                        @Param("fulfillmentStatus") String fulfillmentStatus,
                                        @Param("historyGroup") String historyGroup,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to,
                                        Pageable pageable);

    @Query(value = HISTORY_FULFILLMENT_CTE + """
            select o.status as order_status, coalesce(fs.overall_status, 'NOT_READY') as overall_status,
                   count(o.id) as row_count
            from orders o
            left join fulfillment_summary fs on fs.order_id = o.id
            where o.user_id = :userId
            group by o.status, coalesce(fs.overall_status, 'NOT_READY')
            """, nativeQuery = true)
    List<Object[]> countMineByGroup(@Param("userId") UUID userId);

    @Query("""
            select distinct o from OrderEntity o left join UserEntity u on o.userId = u.id
            where 1 = 1
              and (cast(:orderNumber as string) is null or lower(o.orderNumber) like lower(concat('%', cast(:orderNumber as string), '%')))
              and (cast(:buyer as string) is null or lower(coalesce(u.displayName, '')) like lower(concat('%', cast(:buyer as string), '%'))
                   or lower(coalesce(u.email, '')) like lower(concat('%', cast(:buyer as string), '%')))
              and (:status is null or o.status = :status)
              and (:paymentStatus is null or o.paymentStatus = :paymentStatus)
              and (:attentionOnly = false or o.status = com.utewedding.app.domain.OrderStatus.PAYMENT_REVIEW
                   or o.paymentStatus = com.utewedding.app.domain.PaymentStatus.RECONCILIATION_REQUIRED
                   or (o.status = com.utewedding.app.domain.OrderStatus.PENDING
                       and o.paymentMethod = 'COD' and o.confirmedAt is null)
                   or exists (select cod.id from CodReconciliationEntity cod where cod.orderId = o.id
                              and cod.status in (com.utewedding.app.domain.CodReconciliationStatus.AWAITING_AMOUNT,
                                                 com.utewedding.app.domain.CodReconciliationStatus.REVIEW_REQUIRED))
                   or (o.status = com.utewedding.app.domain.OrderStatus.PAID
                       and not exists (select f0.id from OrderFulfillmentEntity f0 where f0.orderId = o.id))
                   or exists (select f.id from OrderFulfillmentEntity f where f.orderId = o.id and (
                        f.status in (com.utewedding.app.domain.FulfillmentStatus.CONFIRMED,
                                     com.utewedding.app.domain.FulfillmentStatus.PROCESSING)
                        or f.status = com.utewedding.app.domain.FulfillmentStatus.DELIVERY_FAILED
                        or (f.status = com.utewedding.app.domain.FulfillmentStatus.PICKUP_PENDING
                            and f.shipperId is null
                            and exists (select shipment.id from FulfillmentShipmentEntity shipment
                                        where shipment.fulfillmentId = f.id and shipment.providerCode = 'INTERNAL')))))
              and (cast(:from as timestamp) is null or o.createdAt >= :from)
              and (cast(:to as timestamp) is null or o.createdAt < :to)
              and (cast(:shipperId as string) is null or exists (
                    select 1 from OrderFulfillmentEntity shipperFulfillment
                    where shipperFulfillment.orderId = o.id and shipperFulfillment.shipperId = :shipperId))
              and (:fulfillmentStatus is null or exists (
                    select 1 from OrderFulfillmentEntity statusFulfillment
                    where statusFulfillment.orderId = o.id and statusFulfillment.status = :fulfillmentStatus))
            """)
    Page<OrderEntity> searchForAdmin(@Param("orderNumber") String orderNumber,
                                     @Param("buyer") String buyer,
                                     @Param("status") OrderStatus status,
                                     @Param("paymentStatus") PaymentStatus paymentStatus,
                                     @Param("attentionOnly") boolean attentionOnly,
                                     @Param("fulfillmentStatus") com.utewedding.app.domain.FulfillmentStatus fulfillmentStatus,
                                     @Param("shipperId") UUID shipperId,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to,
                                     Pageable pageable);

    List<OrderEntity> findTop20ByUserIdOrderByCreatedAtDesc(UUID userId);

    org.springframework.data.domain.Page<OrderEntity> findByUserIdOrderByCreatedAtDesc(
            UUID userId, org.springframework.data.domain.Pageable pageable);
}
