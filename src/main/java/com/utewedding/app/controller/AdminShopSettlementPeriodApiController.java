package com.utewedding.app.controller;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.dto.response.ShopSettlementPeriodResponse;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.ShopSettlementPeriodService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/shop-settlement-periods")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminShopSettlementPeriodApiController {
    private final ShopSettlementPeriodService periods;
    private final MarketplaceFeatureFlags features;

    public AdminShopSettlementPeriodApiController(ShopSettlementPeriodService periods,
                                                   MarketplaceFeatureFlags features) {
        this.periods = periods;
        this.features = features;
    }

    @PostMapping("/repair")
    public Map<String, Integer> repair() {
        requirePayoutEnabled();
        return Map.of("assignedCount", periods.repairUnassignedEntries());
    }

    @PostMapping("/{periodId}/approve")
    public ShopSettlementPeriodResponse approve(@PathVariable UUID periodId,
                                               @AuthenticationPrincipal UserPrincipal actor) {
        requirePayoutEnabled();
        return ShopSettlementPeriodResponse.from(periods.approve(periodId, actor.getId()));
    }

    @PostMapping("/{periodId}/process")
    public ShopSettlementPeriodResponse process(@PathVariable UUID periodId,
                                               @AuthenticationPrincipal UserPrincipal actor) {
        requirePayoutEnabled();
        return ShopSettlementPeriodResponse.from(periods.beginProcessing(periodId, actor.getId()));
    }

    private void requirePayoutEnabled() {
        if (!features.isEnabled(Feature.PAYOUT)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
