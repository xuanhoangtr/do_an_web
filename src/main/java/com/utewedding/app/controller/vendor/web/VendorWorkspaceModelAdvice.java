package com.utewedding.app.controller.vendor.web;

import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.VendorWorkspaceContext;
import com.utewedding.app.service.impl.VendorWorkspaceNavigation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Adds the checked shop identity and vendor-only menu to every server-rendered vendor page. */
@ControllerAdvice(basePackages = "com.utewedding.app.controller.vendor.web")
public class VendorWorkspaceModelAdvice {
    private final VendorWorkspaceContext workspaceContext;
    private final VendorWorkspaceNavigation navigation;

    public VendorWorkspaceModelAdvice(VendorWorkspaceContext workspaceContext,
                                      VendorWorkspaceNavigation navigation) {
        this.workspaceContext = workspaceContext;
        this.navigation = navigation;
    }

    @ModelAttribute
    public void addVendorWorkspace(Authentication authentication, HttpServletRequest request, Model model) {
        UserPrincipal actor = authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal ? principal : null;
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        String activeItem = activeItem(request.getServletPath());
        model.addAttribute("vendorWorkspace", workspace);
        model.addAttribute("vendorNavActiveItem", activeItem);
        model.addAttribute("vendorNavSections", navigation.sections(activeItem));
    }

    private static String activeItem(String servletPath) {
        if (servletPath == null) return "dashboard";
        if (servletPath.startsWith("/vendor/orders")) return "orders";
        if (servletPath.startsWith("/vendor/chat")) return "chat";
        if (servletPath.startsWith("/vendor/products")) return "products";
        if (servletPath.startsWith("/vendor/inventory")) return "inventory";
        if (servletPath.startsWith("/vendor/store")) return "profile";
        if (servletPath.startsWith("/vendor/vouchers")) return "vouchers";
        if (servletPath.startsWith("/vendor/reviews")) return "reviews";
        if (servletPath.startsWith("/vendor/finance")) return "finance";
        return "dashboard";
    }
}
