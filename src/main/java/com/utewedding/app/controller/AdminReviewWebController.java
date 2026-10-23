package com.utewedding.app.controller;

import com.utewedding.app.domain.ProductReviewStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import com.utewedding.app.dto.request.ModerateProductReviewRequest;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.ReviewService;
import com.utewedding.app.service.web.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminReviewWebController {
    private final ReviewService reviewService;
    private final CurrentUserContext currentUser;

    public AdminReviewWebController(ReviewService reviewService, CurrentUserContext currentUser) {
        this.reviewService = reviewService;
        this.currentUser = currentUser;
    }

    @GetMapping("/admin/reviews")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String queue(@RequestParam(defaultValue = "") String q,
                        @RequestParam(required = false) ProductReviewStatus status,
                        @RequestParam(required = false) Integer rating,
                        @RequestParam(required = false) UUID productId,
                        @RequestParam(required = false) LocalDate from,
                        @RequestParam(required = false) LocalDate to,
                        @RequestParam(defaultValue = "0") int page, Model model) {
        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        model.addAttribute("reviews", reviewService.searchModerationRows(q, status, rating, productId, null,
                from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(), page, 20));
        model.addAttribute("q", q);
        model.addAttribute("status", status == null ? "" : status.name());
        model.addAttribute("rating", rating);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("statuses", ProductReviewStatus.values());
        return "admin/reviews";
    }

    @PostMapping({"/admin/reviews/{reviewId}/approve", "/admin/reviews/{reviewId}/restore"})
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String restore(@PathVariable UUID reviewId, Authentication authentication,
                          HttpServletRequest request, RedirectAttributes attributes) {
        moderate(reviewId, ProductReviewStatus.APPROVED, authentication, request);
        attributes.addFlashAttribute("successMessage", "Đã duyệt đánh giá.");
        return "redirect:/admin/reviews";
    }

    @PostMapping("/admin/reviews/{reviewId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String reject(@PathVariable UUID reviewId, Authentication authentication,
                         HttpServletRequest request, RedirectAttributes attributes) {
        moderate(reviewId, ProductReviewStatus.REJECTED, authentication, request);
        attributes.addFlashAttribute("successMessage", "Đã từ chối đánh giá.");
        return "redirect:/admin/reviews";
    }

    @PostMapping("/admin/reviews/{reviewId}/hide")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
    public String hide(@PathVariable UUID reviewId, Authentication authentication,
                       HttpServletRequest request, RedirectAttributes attributes) {
        moderate(reviewId, ProductReviewStatus.HIDDEN, authentication, request);
        attributes.addFlashAttribute("successMessage", "Đã ẩn đánh giá.");
        return "redirect:/admin/reviews";
    }

    private void moderate(UUID reviewId, ProductReviewStatus status, Authentication authentication,
                          HttpServletRequest request) {
        reviewService.moderate(currentUser.requireUserId(authentication), reviewId,
                new ModerateProductReviewRequest(status),
                new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                        request.getHeader("X-Request-Id")));
    }
}
