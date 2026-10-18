package com.utewedding.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Hides marketplace routes while their server-side rollout flags are disabled. */
public final class RemovedVendorRoutesFilter extends OncePerRequestFilter {
    private static final List<RouteGate> ROUTE_GATES = List.of(
            new RouteGate("/seller", Feature.ONBOARDING),
            new RouteGate("/vendor", Feature.VENDOR_MANAGEMENT),
            new RouteGate("/admin/shops", Feature.VENDOR_MANAGEMENT),
            new RouteGate("/admin/vendors", Feature.VENDOR_MANAGEMENT),
            new RouteGate("/admin/commission", Feature.VENDOR_MANAGEMENT),
            new RouteGate("/api/v1/vendor/finance/payout-destinations", Feature.PAYOUT),
            new RouteGate("/api/v1/vendor/finance/payouts", Feature.PAYOUT),
            new RouteGate("/api/v1/vendor", Feature.VENDOR_MANAGEMENT),
            new RouteGate("/api/vendor", Feature.VENDOR_MANAGEMENT),
            new RouteGate("/api/v1/admin/vendors", Feature.VENDOR_MANAGEMENT),
            new RouteGate("/api/admin/vendors", Feature.VENDOR_MANAGEMENT),
            new RouteGate("/api/v1/admin/commission", Feature.VENDOR_MANAGEMENT),
            new RouteGate("/api/v1/shipping/shop-quotes", Feature.VENDOR_CHECKOUT),
            new RouteGate("/api/shipping/shop-quotes", Feature.VENDOR_CHECKOUT),
            new RouteGate("/admin/settlements", Feature.PAYOUT),
            new RouteGate("/admin/finance/periods", Feature.PAYOUT),
            new RouteGate("/admin/finance/payouts", Feature.PAYOUT),
            new RouteGate("/admin/finance/destinations", Feature.PAYOUT),
            new RouteGate("/admin/finance/holds", Feature.PAYOUT),
            new RouteGate("/api/v1/admin/finance/periods", Feature.PAYOUT),
            new RouteGate("/api/v1/admin/finance/payouts", Feature.PAYOUT),
            new RouteGate("/api/v1/admin/finance/destinations", Feature.PAYOUT),
            new RouteGate("/api/v1/admin/finance/holds", Feature.PAYOUT),
            new RouteGate("/api/v1/admin/settlements", Feature.PAYOUT));

    private final MarketplaceFeatureFlags features;

    public RemovedVendorRoutesFilter() {
        this(new MarketplaceFeatureFlags());
    }

    public RemovedVendorRoutesFilter(MarketplaceFeatureFlags features) {
        this.features = features == null ? new MarketplaceFeatureFlags() : features;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = applicationPath(request);
        if (!isEnabledRoute(path, request)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isEnabledRoute(String path, HttpServletRequest request) {
        // Paused payouts expose only processing records and their transfer evidence
        // recovery actions. The controller checks Admin access and payout state.
        if ("GET".equalsIgnoreCase(request.getMethod()) && !features.isEnabled(Feature.PAYOUT)
                && isAdminPayoutReadRoute(path)) {
            return true;
        }
        if ("POST".equalsIgnoreCase(request.getMethod())
                && (isAdminPayoutActionRoute(path, "paid") || isAdminPayoutActionRoute(path, "proof"))) {
            return true;
        }
        // Shop-scoped checkout shares existing platform URLs. Only the explicit
        // selector opts into the marketplace path. A fingerprinted selection is
        // revalidated against the authenticated cart in OrderWebController, so
        // this filter must not block an available platform group merely because
        // another stale cart group belongs to a disabled vendor shop.
        if ("/checkout".equals(path) && hasText(request.getParameter("shopId"))) {
            return features.isEnabled(Feature.VENDOR_CHECKOUT)
                    || hasText(request.getParameter("selectionFingerprint"));
        }
        if (matchesPrefix(path, "/vendor/vouchers")) {
            return features.isEnabled(Feature.SHOP_VOUCHERS);
        }
        if (matchesPrefix(path, "/vendor/orders")) {
            return features.isEnabled(Feature.VENDOR_ORDERS);
        }
        if (matchesPrefix(path, "/vendor/chat") || matchesPrefix(path, "/support/shops")) {
            return features.isEnabled(Feature.SHOP_CHAT);
        }
        if (isShopChatRoute(path)) {
            return features.isEnabled(Feature.SHOP_CHAT);
        }
        if (matchesPrefix(path, "/shops") || matchesPrefix(path, "/api/v1/shops")) {
            return features.isEnabled(Feature.MARKETPLACE);
        }
        return ROUTE_GATES.stream()
                .filter(gate -> matchesPrefix(path, gate.prefix()))
                .findFirst()
                .map(gate -> features.isEnabled(gate.feature()))
                .orElse(true);
    }

    private static boolean isShopChatRoute(String path) {
        String[] segments = path.split("/");
        return (segments.length >= 4 && "shops".equals(segments[1]) && "chat".equals(segments[3]))
                || (segments.length >= 6 && "api".equals(segments[1]) && "v1".equals(segments[2])
                && "shops".equals(segments[3]) && "chat".equals(segments[5]));
    }

    private static boolean matchesPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static boolean isAdminPayoutReadRoute(String path) {
        String prefix = "/api/v1/admin/finance/payouts";
        if (prefix.equals(path)) return true;
        String itemPrefix = prefix + "/";
        if (!path.startsWith(itemPrefix)) return false;
        return path.substring(itemPrefix.length())
                .matches("(?i)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");
    }

    private static boolean isAdminPayoutActionRoute(String path, String action) {
        String prefix = "/api/v1/admin/finance/payouts/";
        String suffix = "/" + action;
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) return false;
        String payoutId = path.substring(prefix.length(), path.length() - suffix.length());
        return payoutId.matches("(?i)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length()) : uri;
    }

    private record RouteGate(String prefix, Feature feature) {}

    @Configuration(proxyBeanMethods = false)
    static class Registration {
        @Bean
        FilterRegistrationBean<RemovedVendorRoutesFilter> removedVendorRoutesFilterRegistration(
                MarketplaceFeatureFlags features) {
            FilterRegistrationBean<RemovedVendorRoutesFilter> registration =
                    new FilterRegistrationBean<>(new RemovedVendorRoutesFilter(features));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }
}
