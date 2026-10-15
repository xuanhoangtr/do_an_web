package com.utewedding.app.service.impl;

import com.utewedding.app.service.payment.PaymentIntegrityService;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Asks VNPay and MoMo about payment attempts that never received a terminal callback. */
@Component
public class PaymentReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);
    private final PaymentIntegrityService payments;

    public PaymentReconciliationJob(PaymentIntegrityService payments) {
        this.payments = payments;
    }

    @Scheduled(initialDelayString = "${payments.reconciliation.initial-delay-ms:120000}",
            fixedDelayString = "${payments.reconciliation.fixed-delay-ms:300000}")
    @SchedulerLock(name = "payment-reconciliation", lockAtMostFor = "10m")
    public void reconcile() {
        try {
            payments.reconcileStaleAttempts(Instant.now().minusSeconds(120), 50);
        } catch (RuntimeException exception) {
            log.error("Payment reconciliation job failed; it will retry on the next schedule", exception);
        }
    }
}
