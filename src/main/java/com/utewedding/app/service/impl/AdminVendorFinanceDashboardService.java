package com.utewedding.app.service.impl;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.repository.AdminVendorFinanceQueryRepository;
import com.utewedding.app.security.AppRole;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.web.AdminVendorFinanceProjection;
import com.utewedding.app.service.web.VendorDashboardRange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin-only, read-only Vendor finance overview. */
@Service
public class AdminVendorFinanceDashboardService {
    private final MarketplaceFeatureFlags features;
    private final AdminVendorFinanceQueryRepository queries;

    public AdminVendorFinanceDashboardService(MarketplaceFeatureFlags features,
                                              AdminVendorFinanceQueryRepository queries) {
        this.features = features;
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public DashboardView read(UserPrincipal actor, VendorDashboardRange range) {
        if (actor == null || (actor.getRole() != AppRole.ADMIN && actor.getRole() != AppRole.SUPER_ADMIN)) {
            throw new AccessDeniedException("Chỉ quản trị viên được xem tổng hợp tài chính nhà bán.");
        }
        if (!features.isEnabled(Feature.VENDOR_MANAGEMENT) || !features.isEnabled(Feature.PAYOUT)) {
            throw new AccessDeniedException("Tổng hợp tài chính nhà bán hiện chưa được mở.");
        }
        if (range == null) throw new IllegalArgumentException("Finance date range is required.");
        return new DashboardView(range, queries.summary(range.fromInclusive(), range.toExclusive()));
    }

    public record DashboardView(VendorDashboardRange range, AdminVendorFinanceProjection summary) { }
}
