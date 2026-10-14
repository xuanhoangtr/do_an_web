package com.utewedding.app.service.impl;

import com.utewedding.app.service.OrderService;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically expires unpaid orders through the transactional service API. */
@Component
public class PendingOrderExpiryJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(PendingOrderExpiryJob.class);
    private final OrderService orderService;

    public PendingOrderExpiryJob(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(initialDelayString = "${orders.expiry.initial-delay-ms:60000}",
            fixedDelayString = "${orders.expiry.fixed-delay-ms:60000}")
    @SchedulerLock(name = "pending-order-expiry", lockAtMostFor = "5m")
    public void expire() {
        try {
            orderService.expirePendingOrders(Instant.now(), null);
        } catch (RuntimeException exception) {
            LOGGER.error("Pending order expiry job failed; it will retry on the next schedule", exception);
        }
    }
}
