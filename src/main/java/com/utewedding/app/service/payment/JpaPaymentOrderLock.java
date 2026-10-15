package com.utewedding.app.service.payment;

import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaPaymentOrderLock implements PaymentOrderLock {
    private final OrderRepository orders;

    public JpaPaymentOrderLock(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public <T> T withOrderNumber(String orderNumber, Function<OrderEntity, T> work) {
        OrderEntity order = orderNumber == null ? null
                : orders.findByOrderNumberForUpdate(orderNumber).orElse(null);
        return work.apply(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public <T> T withOrderId(UUID orderId, Function<OrderEntity, T> work) {
        OrderEntity order = orderId == null ? null : orders.findByIdForUpdate(orderId).orElse(null);
        return work.apply(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int withExpiredOrders(Instant now, Function<OrderEntity, Boolean> work) {
        int count = 0;
        for (OrderEntity order : orders.findPendingExpiredForUpdate(now)) {
            if (Boolean.TRUE.equals(work.apply(order))) {
                count++;
            }
        }
        return count;
    }
}
