package com.utewedding.app.controller;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.ShopApplicationStatus;
import com.utewedding.app.domain.ShopStatus;
import com.utewedding.app.dto.request.ShopReactivationForm;
import com.utewedding.app.dto.request.ShopReviewReasonForm;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.ShopOnboardingService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Platform administration for reviewing vendor applications and shop status. */
@Controller
@RequestMapping("/admin/shops")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminShopWebController {
    private final ShopOnboardingService onboarding;
    private final MarketplaceFeatureFlags features;

    public AdminShopWebController(ShopOnboardingService onboarding, MarketplaceFeatureFlags features) {
        this.onboarding = onboarding;
        this.features = features;
    }

    @GetMapping({"", "/"})
    public String list(@RequestParam(required = false) ShopStatus status,
                       @RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "0") int page,
                       @AuthenticationPrincipal UserPrincipal actor,
                       HttpServletResponse response, Model model) {
        requireEnabled();
        privateNoStore(response);
        var shops = onboarding.listForAdmin(actor.getId(), status, q, page);
        model.addAttribute("shops", shops);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("q", q);
        model.addAttribute("statuses", List.of(ShopStatus.values()));
        model.addAttribute("statusLabels", shopStatusLabels());
        return "admin/shops/index";
    }

    @GetMapping("/{shopId}")
    public String detail(@PathVariable UUID shopId, @AuthenticationPrincipal UserPrincipal actor,
                         HttpServletResponse response, Model model) {
        requireEnabled();
        privateNoStore(response);
        model.addAttribute("detail", onboarding.adminDetail(shopId, actor.getId()));
        model.addAttribute("statusLabels", shopStatusLabels());
        model.addAttribute("applicationStatusLabels", applicationStatusLabels());
        model.addAttribute("reasonForm", new ShopReviewReasonForm());
        model.addAttribute("reactivationForm", new ShopReactivationForm());
        return "admin/shops/detail";
    }

    @PostMapping("/{shopId}/applications/{applicationId}/approve")
    public String approve(@PathVariable UUID shopId, @PathVariable UUID applicationId,
                         @AuthenticationPrincipal UserPrincipal actor,
                         RedirectAttributes flash) {
        requireEnabled();
        return run(shopId, flash, () -> onboarding.approve(shopId, applicationId, actor.getId()),
                "Đã duyệt hồ sơ và kích hoạt cửa hàng.");
    }

    @PostMapping("/{shopId}/applications/{applicationId}/reject")
    public String reject(@PathVariable UUID shopId, @PathVariable UUID applicationId,
                         @Valid @ModelAttribute("reasonForm") ShopReviewReasonForm form,
                         BindingResult binding, @AuthenticationPrincipal UserPrincipal actor,
                         RedirectAttributes flash) {
        requireEnabled();
        if (binding.hasErrors()) return invalidReason(shopId, flash);
        return run(shopId, flash, () -> onboarding.reject(shopId, applicationId, actor.getId(), form.getReason()),
                "Đã từ chối hồ sơ.");
    }

    @PostMapping("/{shopId}/suspend")
    public String suspend(@PathVariable UUID shopId,
                          @Valid @ModelAttribute("reasonForm") ShopReviewReasonForm form,
                          BindingResult binding, @AuthenticationPrincipal UserPrincipal actor,
                          RedirectAttributes flash) {
        requireEnabled();
        if (binding.hasErrors()) return invalidReason(shopId, flash);
        return run(shopId, flash, () -> onboarding.suspend(shopId, actor.getId(), form.getReason()),
                "Đã tạm ngưng cửa hàng.");
    }

    @PostMapping("/{shopId}/reactivate")
    public String reactivate(@PathVariable UUID shopId,
                             @Valid @ModelAttribute("reactivationForm") ShopReactivationForm form,
                             BindingResult binding, @AuthenticationPrincipal UserPrincipal actor,
                             RedirectAttributes flash) {
        requireEnabled();
        if (binding.hasErrors()) {
            flash.addFlashAttribute("errorMessage", "Lý do khôi phục không được vượt quá 1.000 ký tự.");
            return "redirect:/admin/shops/" + shopId;
        }
        return run(shopId, flash, () -> onboarding.reactivate(shopId, actor.getId(), form.getReason()),
                "Đã khôi phục cửa hàng.");
    }

    @PostMapping("/{shopId}/owner/revoke")
    public String revokeOwner(@PathVariable UUID shopId,
                              @Valid @ModelAttribute("reasonForm") ShopReviewReasonForm form,
                              BindingResult binding, @AuthenticationPrincipal UserPrincipal actor,
                              RedirectAttributes flash) {
        requireEnabled();
        if (binding.hasErrors()) return invalidReason(shopId, flash);
        return run(shopId, flash, () -> onboarding.revokeOwner(shopId, actor.getId(), form.getReason()),
                "Đã thu hồi quyền chủ sở hữu và đăng xuất các phiên hiện có.");
    }

    private String invalidReason(UUID shopId, RedirectAttributes flash) {
        flash.addFlashAttribute("errorMessage", "Lý do cần ít nhất 10 ký tự.");
        return "redirect:/admin/shops/" + shopId;
    }

    private String run(UUID shopId, RedirectAttributes flash, Runnable action, String success) {
        requireEnabled();
        try {
            action.run();
            flash.addFlashAttribute("successMessage", success);
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/admin/shops/" + shopId;
    }

    private void requireEnabled() {
        if (features == null || !features.isEnabled(Feature.VENDOR_MANAGEMENT)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static void privateNoStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Pragma", "no-cache");
    }

    private static Map<String, String> shopStatusLabels() {
        return Map.of(
                ShopStatus.PENDING.name(), "Chờ xét duyệt",
                ShopStatus.ACTIVE.name(), "Đang hoạt động",
                ShopStatus.REJECTED.name(), "Đã từ chối",
                ShopStatus.SUSPENDED.name(), "Đang tạm ngưng",
                ShopStatus.CLOSED.name(), "Đã đóng");
    }

    private static Map<String, String> applicationStatusLabels() {
        return Map.of(
                ShopApplicationStatus.SUBMITTED.name(), "Chờ xét duyệt",
                ShopApplicationStatus.APPROVED.name(), "Đã duyệt",
                ShopApplicationStatus.REJECTED.name(), "Đã từ chối",
                ShopApplicationStatus.WITHDRAWN.name(), "Đã rút hồ sơ");
    }
}
