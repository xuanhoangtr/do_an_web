package com.utewedding.app.controller;

import com.utewedding.app.repository.AuthSessionRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.OrderService;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.dto.request.ChangePasswordForm;
import com.utewedding.app.dto.request.SavedAddressForm;
import com.utewedding.app.service.AuthService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.ShipInfoService;
import com.utewedding.app.service.web.CurrentUserContext;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.validation.Valid;

/** Keeps the account link useful while the profile form is migrated. */
@Controller
public class ProfileWebController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProfileWebController.class);

    private final OrderService orderService;
    private final CurrentUserContext currentUser;
    private final ShipInfoService shipInfoService;
    private final AuthService authService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserRepository userRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuthSessionRepository sessionRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TextSanitizer textSanitizer;

    public ProfileWebController(OrderService orderService, CurrentUserContext currentUser,
                                ShipInfoService shipInfoService, AuthService authService) {
        this.orderService = orderService;
        this.currentUser = currentUser;
        this.shipInfoService = shipInfoService;
        this.authService = authService;
    }

    @GetMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public String profile(Authentication authentication, Model model) {
        UUID userId = currentUser.requireUserId(authentication);
        boolean customer = authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.equals("ROLE_USER") || authority.equals("ROLE_CUSTOMER"));
        model.addAttribute("customerProfile", customer);
        if (customer) {
            try {
                model.addAttribute("orders", orderService.list(userId));
            } catch (RuntimeException exception) {
                log.warn("Could not load recent orders for profile user {}", userId, exception);
                model.addAttribute("orders", java.util.List.of());
            }
            try {
                model.addAttribute("addresses", shipInfoService.list(userId));
            } catch (RuntimeException exception) {
                log.warn("Could not load saved addresses for profile user {}", userId, exception);
                model.addAttribute("addresses", java.util.List.of());
                model.addAttribute("addressLoadError", "Không thể tải sổ địa chỉ lúc này. Vui lòng thử lại.");
            }
            if (!model.containsAttribute("addressForm")) model.addAttribute("addressForm", new SavedAddressForm());
        }
        if (!model.containsAttribute("passwordForm")) model.addAttribute("passwordForm", new ChangePasswordForm());
        if (userRepository != null) {
            userRepository.findById(userId).ifPresent(user -> model.addAttribute("displayName", user.getDisplayName()));
        }
        if (sessionRepository != null) {
            model.addAttribute("sessions", sessionRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId));
        }
        model.addAttribute("profileSection", true);
        return "profile/index";
    }

    @GetMapping("/profile/addresses")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String addresses() {
        return "redirect:/profile#addresses";
    }

    @PostMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public String updateProfile(@RequestParam String displayName, Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        if (userRepository == null) return "redirect:/profile";
        var user = userRepository.findById(currentUser.requireUserId(authentication)).orElseThrow();
        String name = textSanitizer == null ? displayName.trim() : textSanitizer.trimToNull(displayName, 120);
        if (name == null || name.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Tên hiển thị không hợp lệ.");
        } else {
            user.setDisplayName(name);
            userRepository.save(user);
            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật tên hiển thị.");
        }
        return "redirect:/profile";
    }

    @PostMapping("/profile/sessions/{sessionId}/revoke")
    @PreAuthorize("isAuthenticated()")
    public String revokeOwnSession(@PathVariable UUID sessionId, Authentication authentication,
                                   HttpServletRequest request, RedirectAttributes redirectAttributes) {
        UUID userId = currentUser.requireUserId(authentication);
        if (sessionRepository != null) {
            sessionRepository.findById(sessionId).filter(session -> userId.equals(session.getUserId()))
                    .ifPresent(session -> {
                        session.setRevokedAt(java.time.Instant.now());
                        session.setRevokedReason("USER_REVOKED");
                        sessionRepository.save(session);
                    });
        }
        redirectAttributes.addFlashAttribute("successMessage", "Đã thu hồi phiên đăng nhập.");
        return "redirect:/profile";
    }

    @PostMapping("/profile/password")
    @PreAuthorize("isAuthenticated()")
    public String changePassword(Authentication authentication,
                                 @Valid @ModelAttribute("passwordForm") ChangePasswordForm form,
                                 BindingResult result, HttpServletRequest request,
                                 RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Mật khẩu mới chưa hợp lệ; cần ít nhất 10 ký tự gồm chữ hoa, chữ thường và số.");
            return "redirect:/profile";
        }
        try {
            var session = request.getSession(false);
            authService.changePassword(currentUser.requireUserId(authentication), form.toRequest(),
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-Id")),
                    session == null ? null : session.getId());
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đổi mật khẩu thành công. Phiên hiện tại được giữ, các phiên khác đã đăng xuất.");
        } catch (com.utewedding.app.exception.UnauthorizedException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:/profile";
    }

    @PostMapping("/profile/addresses")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String createAddress(Authentication authentication,
                                @Valid @ModelAttribute("addressForm") SavedAddressForm form,
                                BindingResult result, RedirectAttributes redirectAttributes) {
        if (!result.hasErrors()) {
            shipInfoService.create(currentUser.requireUserId(authentication), form.toRequest());
            redirectAttributes.addFlashAttribute("successMessage", "Đã lưu địa chỉ giao hàng.");
        } else redirectAttributes.addFlashAttribute("errorMessage", "Thông tin địa chỉ chưa hợp lệ.");
        return "redirect:/profile";
    }

    @PostMapping("/profile/addresses/{id}")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String updateAddress(@PathVariable UUID id, Authentication authentication,
                                @Valid @ModelAttribute("addressForm") SavedAddressForm form,
                                BindingResult result, RedirectAttributes redirectAttributes) {
        if (!result.hasErrors()) {
            shipInfoService.update(currentUser.requireUserId(authentication), id, form.toRequest());
            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật địa chỉ.");
        } else redirectAttributes.addFlashAttribute("errorMessage", "Thông tin địa chỉ chưa hợp lệ.");
        return "redirect:/profile";
    }

    @PostMapping("/profile/addresses/{id}/default")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String defaultAddress(@PathVariable UUID id, Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        shipInfoService.setDefault(currentUser.requireUserId(authentication), id);
        redirectAttributes.addFlashAttribute("successMessage", "Đã đặt địa chỉ mặc định.");
        return "redirect:/profile";
    }

    @PostMapping("/profile/addresses/{id}/delete")
    @PreAuthorize("hasAnyRole('USER','CUSTOMER')")
    public String deleteAddress(@PathVariable UUID id, Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        shipInfoService.delete(currentUser.requireUserId(authentication), id);
        redirectAttributes.addFlashAttribute("successMessage", "Đã xóa địa chỉ.");
        return "redirect:/profile";
    }
}
