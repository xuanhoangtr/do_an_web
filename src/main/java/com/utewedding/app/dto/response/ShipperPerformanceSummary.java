package com.utewedding.app.dto.response;

public record ShipperPerformanceSummary(
        long attempts,
        long delivered,
        long failed,
        long rescheduled,
        long returned,
        double successRate) {
}
