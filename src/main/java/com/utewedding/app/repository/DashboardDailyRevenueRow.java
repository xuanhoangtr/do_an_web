package com.utewedding.app.repository;

import java.time.LocalDate;

public interface DashboardDailyRevenueRow {
    LocalDate getDate();
    long getAmount();
}
