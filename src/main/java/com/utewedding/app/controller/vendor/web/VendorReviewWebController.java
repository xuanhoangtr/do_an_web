package com.utewedding.app.controller.vendor.web;

import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.VendorReviewService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Vendor review page is read-only; moderation remains an admin responsibility. */
@Controller
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class VendorReviewWebController {
    private final VendorReviewService reviews;

    public VendorReviewWebController(VendorReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/vendor/reviews")
    public String list(Authentication authentication, @RequestParam(defaultValue = "0") int page,
                       HttpServletResponse response, Model model) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        model.addAttribute("reviews", reviews.list(actor(authentication), page));
        return "vendor/reviews/index";
    }

    private static UserPrincipal actor(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal) return principal;
        throw new AccessDeniedException("Bạn không có quyền truy cập đánh giá của cửa hàng.");
    }
}
