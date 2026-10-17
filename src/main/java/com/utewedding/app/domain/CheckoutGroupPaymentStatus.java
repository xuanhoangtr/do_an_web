package com.utewedding.app.domain;

public enum CheckoutGroupPaymentStatus {
    PENDING,
    PROCESSING,
    PAID,
    PARTIALLY_REFUNDED,
    REFUNDED,
    CANCELLED,
    EXPIRED,
    RECONCILIATION_REQUIRED
}
