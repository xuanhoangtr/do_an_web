package com.utewedding.app.domain;

/** How a stored provider callback was classified. Terminal values are safe to replay. */
public enum PaymentCallbackOutcome {
    APPLIED,
    DUPLICATE,
    REJECTED_SIGNATURE,
    PAYMENT_FAILED,
    LATE_CAPTURE,
    AMOUNT_MISMATCH,
    ORDER_NOT_FOUND,
    CONFLICT,
    IGNORED
}
