package com.utewedding.app.domain;

public enum OrderStatus {
    PENDING,
    PAYMENT_REVIEW,
    PAID,
    CANCELLED,
    EXPIRED,
    REFUNDED;

    public boolean canTransitionTo(OrderStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == PAYMENT_REVIEW || target == PAID || target == CANCELLED || target == EXPIRED;
            case PAYMENT_REVIEW -> target == PAID || target == CANCELLED;
            case PAID -> target == REFUNDED;
            case CANCELLED, EXPIRED, REFUNDED -> false;
        };
    }

    public boolean isTerminal() {
        return this == CANCELLED || this == EXPIRED || this == REFUNDED;
    }
}
