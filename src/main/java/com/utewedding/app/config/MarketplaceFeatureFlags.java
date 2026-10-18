package com.utewedding.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Server-side rollout switches for marketplace capabilities.
 *
 * <p>Every sub-feature is subordinate to the top-level marketplace switch so
 * an accidental individual toggle cannot expose a disabled marketplace.</p>
 */
@Component
public final class MarketplaceFeatureFlags {
    public enum Feature {
        MARKETPLACE,
        ONBOARDING,
        VENDOR_MANAGEMENT,
        VENDOR_ORDERS,
        VENDOR_CHECKOUT,
        GROUPED_CHECKOUT,
        MULTI_SHOP_CART,
        SHOP_CHAT,
        SHOP_VOUCHERS,
        PAYOUT
    }

    private final boolean marketplaceEnabled;
    private final boolean onboardingEnabled;
    private final boolean vendorManagementEnabled;
    private final boolean vendorCheckoutEnabled;
    private final boolean groupedCheckoutEnabled;
    private final boolean multiShopCartEnabled;
    private final boolean shopChatEnabled;
    private final boolean shopVouchersEnabled;
    private final boolean payoutEnabled;
    @Value("${app.marketplace.vendor-orders.enabled:false}")
    private boolean vendorOrdersEnabled;

    @Autowired
    public MarketplaceFeatureFlags(
            @Value("${app.marketplace.enabled:false}") boolean marketplaceEnabled,
            @Value("${app.marketplace.onboarding.enabled:false}") boolean onboardingEnabled,
            @Value("${app.marketplace.vendor-management.enabled:false}") boolean vendorManagementEnabled,
            @Value("${app.marketplace.checkout.enabled:false}") boolean vendorCheckoutEnabled,
            @Value("${app.marketplace.grouped-checkout.enabled:false}") boolean groupedCheckoutEnabled,
            @Value("${app.marketplace.multi-shop-cart.enabled:false}") boolean multiShopCartEnabled,
            @Value("${app.marketplace.shop-chat.enabled:false}") boolean shopChatEnabled,
            @Value("${app.marketplace.shop-vouchers.enabled:false}") boolean shopVouchersEnabled,
            @Value("${app.marketplace.payout.enabled:false}") boolean payoutEnabled) {
        this.marketplaceEnabled = marketplaceEnabled;
        this.onboardingEnabled = onboardingEnabled;
        this.vendorManagementEnabled = vendorManagementEnabled;
        this.vendorCheckoutEnabled = vendorCheckoutEnabled;
        this.groupedCheckoutEnabled = groupedCheckoutEnabled;
        this.multiShopCartEnabled = multiShopCartEnabled;
        this.shopChatEnabled = shopChatEnabled;
        this.shopVouchersEnabled = shopVouchersEnabled;
        this.payoutEnabled = payoutEnabled;
    }

    /** Backward-compatible constructor for callers predating grouped checkout. */
    public MarketplaceFeatureFlags(boolean marketplaceEnabled, boolean onboardingEnabled,
                                   boolean vendorManagementEnabled, boolean vendorCheckoutEnabled,
                                   boolean multiShopCartEnabled, boolean shopChatEnabled,
                                   boolean shopVouchersEnabled, boolean payoutEnabled) {
        this(marketplaceEnabled, onboardingEnabled, vendorManagementEnabled, vendorCheckoutEnabled,
                false, multiShopCartEnabled, shopChatEnabled, shopVouchersEnabled, payoutEnabled);
    }

    /** Backward-compatible constructor for tests and non-Spring callers. */
    public MarketplaceFeatureFlags(boolean marketplaceEnabled, boolean onboardingEnabled,
                                   boolean vendorManagementEnabled, boolean vendorCheckoutEnabled,
                                   boolean shopChatEnabled, boolean payoutEnabled) {
        this(marketplaceEnabled, onboardingEnabled, vendorManagementEnabled,
                vendorCheckoutEnabled, false, false, shopChatEnabled, false, payoutEnabled);
    }

    /** Backward-compatible constructor including the shop-voucher flag. */
    public MarketplaceFeatureFlags(boolean marketplaceEnabled, boolean onboardingEnabled,
                                   boolean vendorManagementEnabled, boolean vendorCheckoutEnabled,
                                   boolean shopChatEnabled, boolean shopVouchersEnabled,
                                   boolean payoutEnabled) {
        this(marketplaceEnabled, onboardingEnabled, vendorManagementEnabled,
                vendorCheckoutEnabled, false, false, shopChatEnabled, shopVouchersEnabled, payoutEnabled);
    }

    /** Constructor for tests and conservative non-Spring callers. */
    public MarketplaceFeatureFlags() {
        this(false, false, false, false, false, false, false, false, false);
    }

    public boolean isEnabled(Feature feature) {
        if (feature == null) return false;
        return switch (feature) {
            case MARKETPLACE -> marketplaceEnabled;
            case ONBOARDING -> marketplaceEnabled && onboardingEnabled;
            case VENDOR_MANAGEMENT -> marketplaceEnabled && vendorManagementEnabled;
            case VENDOR_ORDERS -> marketplaceEnabled && vendorManagementEnabled && vendorOrdersEnabled;
            case VENDOR_CHECKOUT -> marketplaceEnabled && vendorCheckoutEnabled;
            case GROUPED_CHECKOUT -> marketplaceEnabled && vendorCheckoutEnabled
                    && multiShopCartEnabled && groupedCheckoutEnabled;
            case MULTI_SHOP_CART -> marketplaceEnabled && vendorCheckoutEnabled && multiShopCartEnabled;
            case SHOP_CHAT -> marketplaceEnabled && shopChatEnabled;
            case SHOP_VOUCHERS -> marketplaceEnabled && vendorManagementEnabled && shopVouchersEnabled;
            case PAYOUT -> marketplaceEnabled && payoutEnabled;
        };
    }
}
