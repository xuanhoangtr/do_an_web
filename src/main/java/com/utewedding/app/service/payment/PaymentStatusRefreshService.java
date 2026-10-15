package com.utewedding.app.service.payment;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.service.RequestContext;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentStatusRefreshService {
    private static final Logger log = LoggerFactory.getLogger(PaymentStatusRefreshService.class);
    private final PaymentIntegrityService payments;
    private final Cache<UUID, Boolean> recent = Caffeine.newBuilder()
            .maximumSize(10000).expireAfterWrite(Duration.ofSeconds(5)).build();
    private final java.util.Set<UUID> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PaymentStatusRefreshService(PaymentIntegrityService payments) {
        this.payments = payments;
    }

    public void refreshPending(OrderEntity order) {
        refreshPending(order, null);
    }

    public void refreshPending(OrderEntity order, RequestContext context) {
        if (order.getStatus() != OrderStatus.PENDING || !"VNPAY".equalsIgnoreCase(order.getPaymentMethod())
                || (order.getPaymentStatus() != PaymentStatus.PENDING
                    && order.getPaymentStatus() != PaymentStatus.PROCESSING)) return;
        refresh(order.getId(), () -> payments.reconcileVnpayOrder(order.getId(), context));
    }

    public void refreshVnpayReference(String providerReference, RequestContext context) {
        PaymentIntegrityService.VnpayAttemptReference resolved =
                payments.resolveVnpayReference(providerReference);
        if (resolved == null) return;
        refresh(resolved.orderId(), () -> payments.reconcileVnpayReference(providerReference, context));
    }

    public com.utewedding.app.domain.PaymentAttemptStatus latestVnpayAttemptStatus(UUID orderId) {
        return payments.vnpayAttemptStatus(orderId);
    }

    private void refresh(UUID orderId, Runnable reconciliation) {
        if (!inFlight.add(orderId)) return;
        try {
            if (recent.asMap().putIfAbsent(orderId, Boolean.TRUE) != null) return;
            reconciliation.run();
        } catch (RuntimeException exception) {
            log.warn("VNPay status refresh failed for order {}", orderId);
        } finally {
            inFlight.remove(orderId);
        }
    }
}
