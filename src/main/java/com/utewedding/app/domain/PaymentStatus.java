package com.utewedding.app.domain;

/**
 * Payment aggregate states. Capture and refund are separate provider
 * transactions; this status is the order-level result of those transactions.
 */
public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    NOT_COLLECTED,
    RECONCILIATION_REQUIRED,
    REFUND_PENDING,
    PARTIALLY_REFUNDED,
    REFUNDED,
    REFUND_FAILED;

    public boolean canTransitionTo(PaymentStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == PROCESSING || target == SUCCEEDED || target == FAILED
                    || target == NOT_COLLECTED || target == RECONCILIATION_REQUIRED;
            case PROCESSING -> target == SUCCEEDED || target == FAILED || target == RECONCILIATION_REQUIRED;
            case RECONCILIATION_REQUIRED -> target == SUCCEEDED || target == FAILED || target == NOT_COLLECTED;
            case SUCCEEDED -> target == REFUND_PENDING || target == PARTIALLY_REFUNDED
                    || target == REFUNDED || target == REFUND_FAILED;
            case REFUND_PENDING -> target == SUCCEEDED || target == PARTIALLY_REFUNDED
                    || target == REFUNDED || target == REFUND_FAILED;
            case PARTIALLY_REFUNDED -> target == REFUND_PENDING || target == REFUNDED || target == REFUND_FAILED;
            case REFUND_FAILED -> target == REFUND_PENDING || target == SUCCEEDED
                    || target == PARTIALLY_REFUNDED || target == REFUNDED;
            case FAILED, NOT_COLLECTED, REFUNDED -> false;
        };
    }

    public boolean isCaptured() {
        return this == SUCCEEDED || this == REFUND_PENDING || this == PARTIALLY_REFUNDED
                || this == REFUNDED || this == REFUND_FAILED;
    }
}
