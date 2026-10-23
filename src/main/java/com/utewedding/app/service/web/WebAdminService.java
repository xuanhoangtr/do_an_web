package com.utewedding.app.service.web;

import java.util.Map;
import java.time.ZoneId;
import java.util.List;
import org.springframework.data.domain.Page;

public interface WebAdminService {
    Map<String, Object> dashboardStats();
    Map<String, Object> dashboardSnapshotStats();
    DashboardAnalytics dashboardAnalytics(DashboardRange range, ZoneId zoneId);
    Page<WebViewModels.AdminUserView> users(int page, int size);
    Page<WebViewModels.AdminUserView> users(AdminUserFilter filter, int page, int size);
    Page<WebViewModels.ProductView> products(String keyword, String status, int page, int size);
    Page<WebViewModels.AdminOrderView> orders(int page, int size);
    List<RevenuePoint> revenueLastSevenDays(ZoneId zoneId);
}
