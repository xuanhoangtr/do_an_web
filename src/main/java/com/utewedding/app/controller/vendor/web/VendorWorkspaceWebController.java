package com.utewedding.app.controller.vendor.web;

import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.VendorDashboardService;
import com.utewedding.app.service.web.VendorDashboardRange;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Initial Vendor workspace landing page. Further modules will reuse the vendor shell fragments. */
@Controller
@RequestMapping("/vendor")
public class VendorWorkspaceWebController {
    private final VendorDashboardService dashboardService;

    public VendorWorkspaceWebController(VendorDashboardService dashboard) {
        this.dashboardService = dashboard;
    }

    @GetMapping({"", "/", "/dashboard"})
    public String dashboard(Authentication authentication,
                            @RequestParam(defaultValue = "last30") String range,
                            @RequestParam(required = false) LocalDate from,
                            @RequestParam(required = false) LocalDate to,
                            HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        VendorDashboardRange selected;
        try {
            selected = VendorDashboardRange.resolve(range, from, to, LocalDate.now(VendorDashboardRange.ZONE));
        } catch (IllegalArgumentException invalidRange) {
            model.addAttribute("rangeError", invalidRange.getMessage());
            selected = VendorDashboardRange.resolve("last30", null, null, LocalDate.now(VendorDashboardRange.ZONE));
        }
        model.addAttribute("dashboard", dashboardService.read(actor(authentication), selected));
        return "vendor/dashboard";
    }

    private static UserPrincipal actor(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal) return principal;
        throw new AccessDeniedException("Bạn không có quyền truy cập cửa hàng.");
    }
}
