package com.utewedding.app.service.impl;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.repository.VendorDashboardQueryRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.web.VendorDashboardMetrics;
import com.utewedding.app.service.web.VendorDashboardOrder;
import com.utewedding.app.service.web.VendorDashboardRange;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only Vendor dashboard resolved against the caller's active shop on every request. */
@Service
public class VendorDashboardService {
    private final VendorWorkspaceContext workspaceContext;
    private final VendorDashboardQueryRepository queries;
    private final MarketplaceFeatureFlags flags;

    public VendorDashboardService(VendorWorkspaceContext workspaceContext,
                                  VendorDashboardQueryRepository queries,
                                  MarketplaceFeatureFlags flags) {
        this.workspaceContext = workspaceContext;
        this.queries = queries;
        this.flags = flags;
    }

    @Transactional(readOnly = true)
    public DashboardView read(UserPrincipal actor, VendorDashboardRange range) {
        if (range == null) throw new IllegalArgumentException("Dashboard range is required.");
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        boolean ordersEnabled = flags.isEnabled(Feature.VENDOR_ORDERS);
        VendorDashboardMetrics metrics = queries.metrics(workspace.shopId(), range.fromInclusive(),
                range.toExclusive(), ordersEnabled);
        List<VendorDashboardOrder> recentOrders = ordersEnabled
                ? queries.recentOrders(workspace.shopId()) : List.of();
        return new DashboardView(workspace.shopName(), range, metrics, recentOrders, ordersEnabled);
    }

    public record DashboardView(String shopName, VendorDashboardRange range,
                                VendorDashboardMetrics metrics, List<VendorDashboardOrder> recentOrders,
                                boolean ordersEnabled) { }
}
