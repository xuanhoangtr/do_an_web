package com.utewedding.app.service.impl;

import java.time.Duration;
import java.time.Instant;

/** Immutable default policy; the serialized values are stored on each case. */
public final class ReturnPolicy {
    public static final Duration REQUEST_WINDOW = Duration.ofDays(7);
    public static final Duration SHIP_WINDOW = Duration.ofDays(7);
    public static final Duration DISPUTE_WINDOW = Duration.ofDays(7);

    public Instant requestDeadline(Instant deliveredAt) { return deliveredAt.plus(REQUEST_WINDOW); }
    public Instant shipDeadline(Instant approvedAt) { return approvedAt.plus(SHIP_WINDOW); }
    public Instant disputeDeadline(Instant rejectedAt) { return rejectedAt.plus(DISPUTE_WINDOW); }
    public boolean requestAllowed(Instant deliveredAt, Instant requestedAt) {
        return !requestedAt.isBefore(deliveredAt) && !requestedAt.isAfter(requestDeadline(deliveredAt));
    }
    public boolean disputeAllowed(Instant rejectedAt, Instant disputedAt) {
        return !disputedAt.isBefore(rejectedAt) && !disputedAt.isAfter(disputeDeadline(rejectedAt));
    }
    public String snapshot() { return "requestDays=7;shipDays=7;disputeDays=7;inclusive=true"; }
}