/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  jakarta.persistence.LockModeType
 *  org.springframework.data.domain.Page
 *  org.springframework.data.domain.Pageable
 *  org.springframework.data.domain.Sort
 *  org.springframework.data.jpa.repository.JpaRepository
 *  org.springframework.data.jpa.repository.Lock
 *  org.springframework.data.jpa.repository.Modifying
 *  org.springframework.data.jpa.repository.Query
 *  org.springframework.data.repository.query.Param
 */
package com.utewedding.app.repository;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.entity.OrderFulfillmentEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderFulfillmentRepository
extends JpaRepository<OrderFulfillmentEntity, UUID> {
    public static final String SHIPPER_BOARD_PREDICATE = "from OrderFulfillmentEntity f, OrderEntity o\nwhere f.orderId = o.id\n  and f.shipperId = :shipperId\n  and (\n        (:history = false and f.status in :workStatuses\n            and o.status not in :terminalOrderStatuses\n            and o.paymentStatus <> :refundedPaymentStatus)\n        or (:history = true and (f.status not in :workStatuses\n            or o.status in :terminalOrderStatuses\n            or o.paymentStatus = :refundedPaymentStatus))\n      )\n  and (:selectedStatus is null or f.status = :selectedStatus)\n";

    public List<OrderFulfillmentEntity> findByOrderIdOrderByCreatedAtAsc(UUID var1);

    public List<OrderFulfillmentEntity> findByOrderIdIn(Collection<UUID> var1, Sort var2);

    public Page<OrderFulfillmentEntity> findAllByOrderByCreatedAtDesc(Pageable var1);

    public Page<OrderFulfillmentEntity> findByShipperId(UUID var1, Pageable var2);

    @Query(value="select f from OrderFulfillmentEntity f, OrderEntity o\nwhere f.orderId = o.id\n  and f.shipperId = :shipperId\n  and (\n        (:history = false and f.status in :workStatuses\n            and o.status not in :terminalOrderStatuses\n            and o.paymentStatus <> :refundedPaymentStatus)\n        or (:history = true and (f.status not in :workStatuses\n            or o.status in :terminalOrderStatuses\n            or o.paymentStatus = :refundedPaymentStatus))\n      )\n  and (:selectedStatus is null or f.status = :selectedStatus)\n", countQuery="select count(f) from OrderFulfillmentEntity f, OrderEntity o\nwhere f.orderId = o.id\n  and f.shipperId = :shipperId\n  and (\n        (:history = false and f.status in :workStatuses\n            and o.status not in :terminalOrderStatuses\n            and o.paymentStatus <> :refundedPaymentStatus)\n        or (:history = true and (f.status not in :workStatuses\n            or o.status in :terminalOrderStatuses\n            or o.paymentStatus = :refundedPaymentStatus))\n      )\n  and (:selectedStatus is null or f.status = :selectedStatus)\n")
    public Page<OrderFulfillmentEntity> findShipperBoardPage(@Param(value="shipperId") UUID var1, @Param(value="history") boolean var2, @Param(value="workStatuses") Collection<FulfillmentStatus> var3, @Param(value="terminalOrderStatuses") Collection<OrderStatus> var4, @Param(value="refundedPaymentStatus") PaymentStatus var5, @Param(value="selectedStatus") FulfillmentStatus var6, Pageable var7);

    @Query(value="select f from OrderFulfillmentEntity f, OrderEntity o\nwhere f.orderId = o.id\n  and f.shipperId = :shipperId\n  and (\n        (:history = false and f.status in :workStatuses\n            and o.status not in :terminalOrderStatuses\n            and o.paymentStatus <> :refundedPaymentStatus)\n        or (:history = true and (f.status not in :workStatuses\n            or o.status in :terminalOrderStatuses\n            or o.paymentStatus = :refundedPaymentStatus))\n      )\n  and (:selectedStatus is null or f.status = :selectedStatus)\n")
    public List<OrderFulfillmentEntity> findShipperBoardRows(@Param(value="shipperId") UUID var1, @Param(value="history") boolean var2, @Param(value="workStatuses") Collection<FulfillmentStatus> var3, @Param(value="terminalOrderStatuses") Collection<OrderStatus> var4, @Param(value="refundedPaymentStatus") PaymentStatus var5, @Param(value="selectedStatus") FulfillmentStatus var6, Sort var7);

    @Query(value="select count(f) from OrderFulfillmentEntity f, OrderEntity o where f.orderId = o.id and f.shipperId = :shipperId and f.status in :statuses and o.status not in :terminalOrderStatuses and o.paymentStatus <> :refundedPaymentStatus")
    public long countActiveShipperFulfillments(@Param(value="shipperId") UUID var1, @Param(value="statuses") Collection<FulfillmentStatus> var2, @Param(value="terminalOrderStatuses") Collection<OrderStatus> var3, @Param(value="refundedPaymentStatus") PaymentStatus var4);

    public long countByShipperId(UUID var1);

    public long countByShipperIdAndStatus(UUID var1, FulfillmentStatus var2);

    public long countByShipperIdAndStatusAndDeliveredAtGreaterThanEqualAndDeliveredAtLessThan(UUID var1, FulfillmentStatus var2, Instant var3, Instant var4);

    @Lock(value=LockModeType.PESSIMISTIC_WRITE)
    @Query(value="select f from OrderFulfillmentEntity f where f.id = :id")
    public Optional<OrderFulfillmentEntity> findByIdForUpdate(@Param(value="id") UUID var1);

    @Modifying(flushAutomatically=true)
    @Query(value="UPDATE order_fulfillments SET status = :target, version = version + 1, updated_at = :now WHERE id = :id AND status = :expected AND version = :version", nativeQuery=true)
    public int compareAndSetStatus(@Param(value="id") UUID var1, @Param(value="expected") String var2, @Param(value="target") String var3, @Param(value="version") long var4, @Param(value="now") Instant var6);

    @Query(value="select f from OrderFulfillmentEntity f where f.orderId = :orderId and f.status in :statuses")
    public List<OrderFulfillmentEntity> findByOrderIdAndStatusIn(@Param(value="orderId") UUID var1, @Param(value="statuses") Collection<FulfillmentStatus> var2);

    public long countByStatus(FulfillmentStatus var1);

    @Lock(value=LockModeType.PESSIMISTIC_WRITE)
    @Query(value="select f from OrderFulfillmentEntity f where f.orderId = :orderId order by f.createdAt asc, f.id asc")
    public List<OrderFulfillmentEntity> findByOrderIdForUpdate(@Param(value="orderId") UUID var1);

    @Lock(value=LockModeType.PESSIMISTIC_WRITE)
    @Query(value="select f from OrderFulfillmentEntity f where f.orderId = :orderId order by f.id asc")
    public List<OrderFulfillmentEntity> findByOrderIdForUpdateOrderByIdAsc(@Param(value="orderId") UUID var1);

    @Query(value="select f from OrderFulfillmentEntity f, OrderEntity o\nwhere f.orderId = o.id\n  and (:status is null or f.status = :status)\n  and (cast(:shipperId as string) is null or f.shipperId = :shipperId)\n  and (cast(:from as timestamp) is null or f.createdAt >= :from)\n  and (cast(:to as timestamp) is null or f.createdAt < :to)\n  and (cast(:query as string) is null or lower(o.orderNumber) like lower(concat('%', cast(:query as string), '%'))\n       or lower(coalesce(f.trackingNumber, '')) like lower(concat('%', cast(:query as string), '%'))\n       or lower(coalesce(o.shippingRecipientName, '')) like lower(concat('%', cast(:query as string), '%')))\n")
    public Page<OrderFulfillmentEntity> searchForAdmin(@Param(value="query") String var1, @Param(value="status") FulfillmentStatus var2, @Param(value="shipperId") UUID var3, @Param(value="from") Instant var4, @Param(value="to") Instant var5, Pageable var6);
}
