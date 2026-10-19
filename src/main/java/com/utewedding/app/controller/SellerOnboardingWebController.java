package com.utewedding.app.controller;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.dto.request.ShopApplicationForm;
import com.utewedding.app.service.impl.ShopOnboardingService;
import com.utewedding.app.service.web.CurrentUserContext;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Customer-facing seller application page; submitting never changes the customer's platform role. */
@Controller
@RequestMapping("/seller/onboarding")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class SellerOnboardingWebController {
    private final ShopOnboardingService onboarding;
    private final CurrentUserContext currentUser;
    private final MarketplaceFeatureFlags features;

    public SellerOnboardingWebController(ShopOnboardingService onboarding, CurrentUserContext currentUser,
                                         MarketplaceFeatureFlags features) {
        this.onboarding = onboarding;
        this.currentUser = currentUser;
        this.features = features;
    }

    @GetMapping
    public String page(Authentication authentication, HttpServletResponse response, Model model) {
        requireEnabled();
        privateNoStore(response);
        var state = onboarding.onboardingFor(currentUser.requireUserId(authentication));
        model.addAttribute("onboarding", state);
        model.addAttribute("applicationForm", state.form());
        return "seller/onboarding";
    }

    @PostMapping
    public String submit(@Valid @ModelAttribute("applicationForm") ShopApplicationForm form,
                         BindingResult binding, Authentication authentication,
                         HttpServletResponse response, Model model, RedirectAttributes flash) {
        requireEnabled();
        privateNoStore(response);
        var userId = currentUser.requireUserId(authentication);
        if (binding.hasErrors()) {
            model.addAttribute("onboarding", onboarding.onboardingFor(userId));
            model.addAttribute("applicationForm", form);
            model.addAttribute("formError", "Vui lòng kiểm tra lại thông tin hồ sơ.");
            return "seller/onboarding";
        }
        try {
            onboarding.submit(userId, form);
            flash.addFlashAttribute("successMessage", "Đã gửi hồ sơ. Quản trị viên sẽ xem xét và thông báo kết quả.");
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/seller/onboarding";
    }

    private void requireEnabled() {
        if (features == null || !features.isEnabled(Feature.ONBOARDING)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static void privateNoStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Pragma", "no-cache");
    }
}
