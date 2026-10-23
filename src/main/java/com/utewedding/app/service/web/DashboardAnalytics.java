package com.utewedding.app.service.web;

import java.util.List;

public record DashboardAnalytics(
        DashboardRange range,
        DashboardRange previousRange,
        DashboardMetric revenue,
        DashboardMetric orders,
        DashboardMetric newUsers,
        List<RevenuePoint> revenueByDay) {
}
