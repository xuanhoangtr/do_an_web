package com.utewedding.app.controller.vendor.web;

import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.VendorFinanceService;
import com.utewedding.app.service.web.VendorDashboardRange;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/vendor/finance")
public class VendorFinanceWebController {
    private final VendorFinanceService finance;

    public VendorFinanceWebController(VendorFinanceService finance) { this.finance = finance; }

    @GetMapping
    public String index(Authentication authentication,
                        @RequestParam(defaultValue = "last30") String range,
                        @RequestParam(required = false) LocalDate from,
                        @RequestParam(required = false) LocalDate to,
                        @RequestParam(defaultValue = "0") int page,
                        HttpServletResponse response, Model model) {
        privateNoStore(response);
        VendorDashboardRange selected = resolveRange(range, from, to, model);
        model.addAttribute("finance", finance.read(actor(authentication), selected, page));
        return "vendor/finance";
    }

    @GetMapping("/export.csv")
    public void export(Authentication authentication,
                       @RequestParam(defaultValue = "last30") String range,
                       @RequestParam(required = false) LocalDate from,
                       @RequestParam(required = false) LocalDate to,
                       HttpServletResponse response, Model model) throws java.io.IOException {
        privateNoStore(response);
        VendorDashboardRange selected = resolveRange(range, from, to, model);
        byte[] csv = finance.exportCsv(actor(authentication), selected);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("vendor-finance-" + selected.from() + "-" + selected.to() + ".csv").build().toString());
        response.getOutputStream().write(csv);
    }

    private static VendorDashboardRange resolveRange(String range, LocalDate from, LocalDate to, Model model) {
        LocalDate today = LocalDate.now(VendorDashboardRange.ZONE);
        try {
            return VendorDashboardRange.resolve(range, from, to, today);
        } catch (IllegalArgumentException invalid) {
            model.addAttribute("rangeError", invalid.getMessage());
            return VendorDashboardRange.resolve("last30", null, null, today);
        }
    }

    private static void privateNoStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    private static UserPrincipal actor(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal) return principal;
        throw new AccessDeniedException("Bạn không có quyền truy cập tài chính cửa hàng.");
    }
}
