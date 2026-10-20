package com.utewedding.app.domain;

public enum SupportTicketStatus {
    OPEN, IN_PROGRESS, WAITING_CUSTOMER, RESOLVED, CLOSED;

    public boolean canTransitionTo(SupportTicketStatus target, boolean customerReplyWithinWindow) {
        if (this == RESOLVED && target == OPEN) return customerReplyWithinWindow;
        return switch (this) {
            case OPEN -> target == IN_PROGRESS || target == WAITING_CUSTOMER || target == RESOLVED;
            case IN_PROGRESS -> target == WAITING_CUSTOMER || target == RESOLVED;
            case WAITING_CUSTOMER -> target == IN_PROGRESS || target == RESOLVED;
            case RESOLVED -> target == CLOSED;
            case CLOSED -> false;
        };
    }
}
