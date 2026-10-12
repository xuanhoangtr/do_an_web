package com.utewedding.app.controller;

import com.utewedding.app.service.impl.RecentlyViewedProductService;
import com.utewedding.app.service.web.CurrentUserContext;
import jakarta.servlet.http.HttpServletResponse;
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
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class RecentlyViewedWebController {
    private final RecentlyViewedProductService recentlyViewed;
    private final CurrentUserContext currentUser;

    public RecentlyViewedWebController(RecentlyViewedProductService recentlyViewed,
                                       CurrentUserContext currentUser) {
        this.recentlyViewed = recentlyViewed;
        this.currentUser = currentUser;
    }

    @GetMapping("/account/recently-viewed")
    public String list(@RequestParam(defaultValue = "0") int page, Authentication authentication,
                       Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        int pageNumber = Math.max(0, page);
        var products = recentlyViewed.list(currentUser.requireUserId(authentication), pageNumber);
        if (pageNumber > 0 && products.isEmpty()) {
            return "redirect:/account/recently-viewed";
        }
        model.addAttribute("recentlyViewed", products);
        return "account/recently-viewed";
    }

    @PostMapping("/account/recently-viewed/{productId}/remove")
    public String remove(@PathVariable UUID productId,
                         @RequestParam(defaultValue = "0") int page,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        UUID userId = currentUser.requireUserId(authentication);
        recentlyViewed.remove(userId, productId);
        int pageNumber = Math.max(0, page);
        if (pageNumber > 0 && recentlyViewed.list(userId, pageNumber).isEmpty()) {
            pageNumber = 0;
        }
        redirectAttributes.addFlashAttribute("successMessage", "Đã xóa sản phẩm khỏi lịch sử đã xem.");
        return "redirect:/account/recently-viewed?page=" + pageNumber;
    }

    @PostMapping("/account/recently-viewed/clear")
    public String clear(Authentication authentication, RedirectAttributes redirectAttributes) {
        recentlyViewed.clear(currentUser.requireUserId(authentication));
        redirectAttributes.addFlashAttribute("successMessage", "Đã xóa toàn bộ lịch sử sản phẩm đã xem.");
        return "redirect:/account/recently-viewed";
    }
}
