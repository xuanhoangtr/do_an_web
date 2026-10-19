package com.utewedding.app.service.impl;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Vendor-only navigation model. Unimplemented destinations are deliberately non-interactive. */
@Component
public class VendorWorkspaceNavigation {
    private final MarketplaceFeatureFlags features;

    @Autowired
    public VendorWorkspaceNavigation(MarketplaceFeatureFlags features) {
        this.features = features;
    }

    public VendorWorkspaceNavigation() {
        this(new MarketplaceFeatureFlags());
    }

    public List<Section> sections(String activeItem) {
        List<Section> menu = List.of(
                new Section("Không gian", List.of(
                        new Item("dashboard", "Tổng quan", "space_dashboard", "/vendor", true))),
                new Section("Tài chính", List.of(
                        new Item("finance", "Tài chính", "account_balance_wallet", "/vendor/finance", true))),
                new Section("Vận hành", List.of(
                        new Item("orders", "Đơn hàng", "shopping_bag",
                                features.isEnabled(Feature.VENDOR_ORDERS) ? "/vendor/orders" : null,
                                features.isEnabled(Feature.VENDOR_ORDERS)),
                        new Item("chat", "Tin nhắn", "chat",
                                features.isEnabled(Feature.SHOP_CHAT) ? "/vendor/chat" : null,
                                features.isEnabled(Feature.SHOP_CHAT)),
                        new Item("products", "Sản phẩm", "inventory_2", "/vendor/products", true),
                        new Item("inventory", "Tồn kho", "warehouse", "/vendor/inventory", true))),
                new Section("Cửa hàng", List.of(
                        new Item("profile", "Hồ sơ cửa hàng", "storefront", "/vendor/store", true),
                        new Item("vouchers", "Mã giảm giá", "sell",
                                features != null && features.isEnabled(Feature.SHOP_VOUCHERS)
                                        ? "/vendor/vouchers" : null,
                                features != null && features.isEnabled(Feature.SHOP_VOUCHERS)),
                        new Item("reviews", "Đánh giá", "star", "/vendor/reviews", true))));
        return menu.stream()
                .map(section -> new Section(section.label(), section.items().stream()
                        .map(item -> new Item(item.key(), item.label(), item.icon(), item.href(), item.enabled(),
                                item.key().equals(activeItem)))
                        .toList()))
                .toList();
    }

    public record Section(String label, List<Item> items) { }

    public record Item(String key, String label, String icon, String href, boolean enabled, boolean active) {
        private Item(String key, String label, String icon, String href, boolean enabled) {
            this(key, label, icon, href, enabled, false);
        }
    }
}
