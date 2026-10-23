package com.utewedding.app.repository;

public interface DashboardMetricRow {
    String getPeriod();
    long getRevenue();
    long getOrders();
    long getNewUsers();
}
