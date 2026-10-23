package com.utewedding.app.controller;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Supplies the rollout-gated Admin shop navigation state to shared dashboard fragments. */
@ControllerAdvice(basePackages = "com.utewedding.app.controller")
public class AdminMarketplaceNavigationAdvice {
    private MarketplaceFeatureFlags features = new MarketplaceFeatureFlags();

    @Autowired(required = false)
    void setFeatures(MarketplaceFeatureFlags features) {
        this.features = features == null ? new MarketplaceFeatureFlags() : features;
    }

    @ModelAttribute
    public void addShopNavigation(Model model) {
        model.addAttribute("adminShopManagementEnabled",
                features.isEnabled(Feature.VENDOR_MANAGEMENT));
        model.addAttribute("adminVendorFinanceEnabled",
                features.isEnabled(Feature.VENDOR_MANAGEMENT) && features.isEnabled(Feature.PAYOUT));
    }
}
