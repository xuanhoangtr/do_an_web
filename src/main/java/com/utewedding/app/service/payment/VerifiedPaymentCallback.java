package com.utewedding.app.service.payment;

import java.time.Instant;

/** A signature-checked provider callback. Amount is in major currency units (VND). */
public record VerifiedPaymentCallback(
        String provider,
        String orderNumber,
        String providerTransactionId,
        String providerRequestId,
        Long amount,
        boolean success,
        String responseCode,
        String transactionStatus,
        String message,
        String canonicalPayload,
        Instant receivedAt) {
}
