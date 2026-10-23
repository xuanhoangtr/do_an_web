package com.utewedding.app.service.web;

public record DashboardMetric(long current, long previous) {
    public long delta() {
        return current - previous;
    }

    public Double percentageChange() {
        return previous == 0 ? null : ((double) delta() / previous) * 100;
    }
}
