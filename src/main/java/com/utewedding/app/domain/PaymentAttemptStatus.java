package com.utewedding.app.domain;

/** One redirect or provider session. Terminal once the provider answers or the order times out. */
public enum PaymentAttemptStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    EXPIRED;

    public boolean isOpen() {
        return this == PENDING || this == PROCESSING;
    }
}
