package com.utewedding.app.service.impl;

import com.utewedding.app.service.payment.PaymentIntegrityService;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries committed refund intents after provider timeouts and late captures. */
@Component
public class PaymentRefundIntentJob {
    private static final Logger log = LoggerFactory.getLogger(PaymentRefundIntentJob.class);
    private final PaymentIntegrityService payments;

    public PaymentRefundIntentJob(PaymentIntegrityService payments) {
        this.payments = payments;
    }

    @Scheduled(initialDelayString = "${payments.refund-intents.initial-delay-ms:60000}",
            fixedDelayString = "${payments.refund-intents.fixed-delay-ms:60000}")
    @SchedulerLock(name = "payment-refund-intents", lockAtMostFor = "10m")
    public void processPendingRefunds() {
        try {
            payments.processPendingRefundIntents(Instant.now().minusSeconds(120), 50);
        } catch (RuntimeException exception) {
            log.error("Refund intent worker failed; it will retry on the next schedule", exception);
        }
    }
}
