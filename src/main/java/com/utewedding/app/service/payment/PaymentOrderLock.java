package com.utewedding.app.service.payment;

import com.utewedding.app.domain.entity.OrderEntity;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

/** Serializes payment mutations on one order row. */
public interface PaymentOrderLock {
    <T> T withOrderNumber(String orderNumber, Function<OrderEntity, T> work);
    <T> T withOrderId(UUID orderId, Function<OrderEntity, T> work);
    int withExpiredOrders(Instant now, Function<OrderEntity, Boolean> work);
}
