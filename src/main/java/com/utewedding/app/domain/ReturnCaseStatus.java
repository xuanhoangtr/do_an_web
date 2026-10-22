package com.utewedding.app.domain;

public enum ReturnCaseStatus {
    REQUESTED,
    UNDER_REVIEW,
    APPROVED,
    REJECTED,
    AWAITING_RETURN,
    RETURN_IN_TRANSIT,
    RECEIVED,
    INSPECTED,
    RESOLUTION_PENDING,
    REFUND_PENDING,
    REFUND_FAILED,
    EXCHANGE_PENDING,
    RESOLVED,
    DISPUTED,
    CLOSED,
    EXPIRED,
    CANCELLED;

    public boolean canTransitionTo(ReturnCaseStatus target) {
        if (target == null) return false;
        if (target == this) return true;
        return switch (this) {
            case REQUESTED -> target == UNDER_REVIEW || target == CANCELLED;
            case UNDER_REVIEW -> target == APPROVED || target == REJECTED;
            case APPROVED -> target == AWAITING_RETURN || target == RESOLUTION_PENDING;
            case AWAITING_RETURN -> target == RETURN_IN_TRANSIT || target == EXPIRED;
            case RETURN_IN_TRANSIT -> target == RECEIVED;
            case RECEIVED -> target == INSPECTED;
            case INSPECTED -> target == RESOLUTION_PENDING || target == REJECTED;
            case RESOLUTION_PENDING -> target == REFUND_PENDING || target == EXCHANGE_PENDING;
            case REFUND_PENDING -> target == RESOLVED || target == REFUND_FAILED;
            case REFUND_FAILED -> target == REFUND_PENDING;
            case EXCHANGE_PENDING -> target == RESOLVED;
            case REJECTED -> target == DISPUTED;
            case DISPUTED -> target == CLOSED || target == APPROVED || target == RESOLUTION_PENDING;
            case RESOLVED -> target == CLOSED;
            case CANCELLED, EXPIRED, CLOSED -> false;
        };
    }

    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED || this == EXPIRED;
    }
}