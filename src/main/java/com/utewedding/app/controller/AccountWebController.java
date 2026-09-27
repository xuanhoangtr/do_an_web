package com.utewedding.app.controller;

import com.utewedding.app.dto.request.UpdateProductReviewRequest;
import com.utewedding.app.repository.UserNotificationRepository;
import com.utewedding.app.service.ProductFavoriteService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.ReviewService;
import com.utewedding.app.service.web.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AccountWebController {
    private final CurrentUserContext currentUser;
    private final ProductFavoriteService favorites;
    private final ReviewService reviews;
    private final UserNotificationRepository notifications;

    public AccountWebController(CurrentUserContext currentUser, ProductFavoriteService favorites,
                                ReviewService reviews, UserNotificationRepository notifications) {
        this.currentUser = currentUser;
        this.favorites = favorites;
        this.reviews = reviews;
        this.notifications = notifications;
    }

    @GetMapping("/account/favorites")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String favorites(Authentication authentication, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("favorites", favorites.list(currentUser.requireUserId(authentication),
                PageRequest.of(Math.max(page, 0), 12)));
        return "account/favorites";
    }

    @PostMapping("/account/favorites/{productId}/remove")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String removeFavorite(@PathVariable UUID productId, Authentication authentication,
                                 HttpServletRequest request, RedirectAttributes redirectAttributes) {
        favorites.remove(currentUser.requireUserId(authentication), productId, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã bỏ sản phẩm khỏi danh sách yêu thích.");
        return "redirect:/account/favorites";
    }

    @GetMapping("/account/reviews")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String reviews(Authentication authentication, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("reviews", reviews.mine(currentUser.requireUserId(authentication), page, 10));
        return "account/reviews";
    }

    @PostMapping("/account/reviews/{reviewId}")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String updateReview(@PathVariable UUID reviewId, @RequestParam int rating, @RequestParam String content,
                               Authentication authentication, HttpServletRequest request,
                               RedirectAttributes redirectAttributes) {
        try {
            reviews.update(currentUser.requireUserId(authentication), reviewId,
                    new UpdateProductReviewRequest(rating, content), context(request));
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã cập nhật đánh giá. Nội dung sẽ chờ duyệt lại.");
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/account/reviews";
    }

    @PostMapping("/account/reviews/{reviewId}/delete")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String deleteReview(@PathVariable UUID reviewId, Authentication authentication,
                               HttpServletRequest request, RedirectAttributes redirectAttributes) {
        reviews.delete(currentUser.requireUserId(authentication), reviewId, context(request));
        redirectAttributes.addFlashAttribute("successMessage", "Đã xóa đánh giá.");
        return "redirect:/account/reviews";
    }

    @GetMapping("/notifications")
    @PreAuthorize("isAuthenticated()")
    public String notifications(Authentication authentication, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("notifications", notifications.findByRecipientIdOrderByCreatedAtDesc(
                currentUser.requireUserId(authentication), PageRequest.of(Math.max(page, 0), 20)));
        return "account/notifications";
    }

    @GetMapping("/notifications/unread-count")
    @ResponseBody
    @PreAuthorize("isAuthenticated()")
    public Map<String, Long> unread(Authentication authentication) {
        return Map.of("count", notifications.countByRecipientIdAndReadAtIsNull(
                currentUser.requireUserId(authentication)));
    }

    @PostMapping("/notifications/{id}/read")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public String readOne(@PathVariable UUID id, Authentication authentication) {
        UUID userId = currentUser.requireUserId(authentication);
        var notice = notifications.findById(id).filter(row -> userId.equals(row.getRecipientId())).orElse(null);
        notifications.markRead(id, userId, Instant.now());
        if (notice != null && notice.getTargetUrl() != null && notice.getTargetUrl().startsWith("/")
                && !notice.getTargetUrl().startsWith("//")) {
            return "redirect:" + notice.getTargetUrl();
        }
        return "redirect:/notifications";
    }

    @PostMapping("/notifications/read-all")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public String readAll(Authentication authentication) {
        notifications.markAllRead(currentUser.requireUserId(authentication), Instant.now());
        return "redirect:/notifications";
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }
}
