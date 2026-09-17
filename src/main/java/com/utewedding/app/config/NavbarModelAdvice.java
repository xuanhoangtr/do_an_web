package com.utewedding.app.config;

import com.utewedding.app.dto.response.CartItemResponse;
import com.utewedding.app.repository.UserNotificationRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.CartService;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Supplies shared navigation data for customer and staff layouts. */
@ControllerAdvice(annotations = Controller.class)
public class NavbarModelAdvice {
    private static final Set<String> CUSTOMER_ROLES = Set.of("ROLE_USER", "ROLE_CUSTOMER");

    private final ObjectProvider<CartService> cartServiceProvider;
    private final ObjectProvider<UserNotificationRepository> notifications;
    private com.utewedding.app.service.impl.AdminAttentionService adminAttentionService;

    public NavbarModelAdvice(org.springframework.beans.factory.ObjectProvider<CartService> cartServiceProvider,
                             org.springframework.beans.factory.ObjectProvider<UserNotificationRepository> notifications) {
        this.cartServiceProvider = cartServiceProvider;
        this.notifications = notifications;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachAdminAttentionService(com.utewedding.app.service.impl.AdminAttentionService service) {
        this.adminAttentionService = service;
    }

    @ModelAttribute
    public void navigation(Authentication authentication, org.springframework.ui.Model model) {
        model.addAttribute("roleHome", SecurityConfig.loginSuccessUrl(authentication));
        var roles = authentication == null ? java.util.Set.<String>of() : authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority).collect(java.util.stream.Collectors.toSet());
        model.addAttribute("profileRole", roles.stream().filter(role -> role.startsWith("ROLE_")).sorted()
                .map(role -> role.substring(5)).findFirst().orElse("USER"));
        model.addAttribute("operationsHome", roles.contains("ROLE_SHIPPER") ? "/shipper/board"
                : roles.stream().anyMatch(role -> java.util.Set.of("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_SUPER_ADMIN").contains(role))
                ? "/admin/orders" : "/");
        boolean operationsUser = roles.stream().anyMatch(role -> java.util.Set.of(
                "ROLE_ADMIN", "ROLE_MANAGER", "ROLE_SUPER_ADMIN").contains(role));
        model.addAttribute("adminAttention", operationsUser && adminAttentionService != null
                ? adminAttentionService.snapshot() : null);
    }

    @ModelAttribute("headerAuthenticated")
    public boolean headerAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getPrincipal() instanceof UserPrincipal;
    }

    @ModelAttribute("adminNavActiveItem")
    public String adminNavActiveItem(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.startsWith("/admin/orders")) return "orders";
        if (path.startsWith("/admin/payments")) return "payments";
        if (path.startsWith("/admin/fulfillments")) return "fulfillments";
        if (path.startsWith("/admin/returns")) return "returns";
        if (path.startsWith("/admin/support")) return "support";
        if (path.startsWith("/admin/risk-events")) return "risk";
        if (path.startsWith("/admin/shipping-operations")) return "shipping";
        if (path.startsWith("/admin/finance")) return "finance";
        if (path.startsWith("/admin/vouchers")) return "vouchers";
        if (path.startsWith("/admin/products")) return "products";
        if (path.startsWith("/admin/categories")) return "categories";
        if (path.startsWith("/admin/review-reports")) return "reviewReports";
        if (path.startsWith("/admin/reviews")) return "reviews";
        if (path.startsWith("/admin/weddings")) return "weddings";
        if (path.startsWith("/admin/users")) return "users";
        if (path.startsWith("/admin/audit-logs")) return "audit";
        if (path.equals("/admin") || path.startsWith("/admin/dashboard")) return "dashboard";
        return "";
    }

    @ModelAttribute("headerUnreadNotifications")
    public long headerUnreadNotifications(Authentication authentication) {
        if (!headerAuthenticated(authentication)) return 0;
        UserNotificationRepository repository = notifications.getIfAvailable();
        if (repository == null) return 0;
        return repository.countByRecipientIdAndReadAtIsNull(((UserPrincipal) authentication.getPrincipal()).getId());
    }

    @ModelAttribute("headerDisplayName")
    public String headerDisplayName(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal user
                && user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        return authentication == null ? "" : authentication.getName();
    }

    @ModelAttribute("headerCartCount")
    public int headerCartCount(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal user)
                || authentication.getAuthorities().stream()
                        .noneMatch(authority -> CUSTOMER_ROLES.contains(authority.getAuthority()))) {
            return 0;
        }
        CartService cartService = cartServiceProvider.getIfAvailable();
        if (cartService == null) {
            return 0;
        }
        var cart = cartService.getCart(user.getId());
        if (cart == null || cart.items() == null) return 0;
        return cart.items().stream()
                .mapToInt(CartItemResponse::quantity)
                .sum();
    }
}
