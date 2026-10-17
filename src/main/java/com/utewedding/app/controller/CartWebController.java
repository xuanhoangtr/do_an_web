package com.utewedding.app.controller;

import com.utewedding.app.service.web.CurrentUserContext;
import com.utewedding.app.service.web.WebCartService;
import com.utewedding.app.service.web.WebViewModels;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@Validated
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class CartWebController {

    private final WebCartService cartService;
    private final CurrentUserContext currentUser;
    private com.utewedding.app.config.MarketplaceFeatureFlags featureFlags;

    public CartWebController(WebCartService cartService, CurrentUserContext currentUser) {
        this.cartService = cartService;
        this.currentUser = currentUser;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachFeatureFlags(com.utewedding.app.config.MarketplaceFeatureFlags featureFlags) {
        this.featureFlags = featureFlags;
    }

    @GetMapping("/cart")
    public String cart(Authentication authentication, Model model) {
        populateCart(authentication, model);
        return "cart/cart";
    }

    @PostMapping("/cart/add")
    public String add(Authentication authentication,
                      @RequestParam UUID productId,
                      @RequestParam(defaultValue = "1") @Min(1) int quantity,
                      @RequestParam(required = false) String customizationText,
                      HttpServletRequest request,
                      Model model,
                      RedirectAttributes redirectAttributes) {
        try {
            cartService.add(currentUser.requireUserId(authentication), productId, quantity,
                    customizationText);
            return afterMutation(authentication, request, model, redirectAttributes,
                    "Đã thêm sản phẩm vào giỏ hàng.", false);
        } catch (RuntimeException exception) {
            return afterMutationError(authentication, request, model, redirectAttributes, exception,
                    false);
        }
    }

    @PostMapping("/cart/update")
    public String update(Authentication authentication,
                         @RequestParam UUID cartItemId,
                         @RequestParam String action,
                         HttpServletRequest request,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        return updateInternal(authentication, cartItemId, action, request, model, redirectAttributes);
    }

    @PostMapping({"/cart/remove/{id}", "/cart/items/{id}/remove"})
    public String remove(Authentication authentication,
                         @PathVariable UUID id,
                         HttpServletRequest request,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        try {
            cartService.remove(currentUser.requireUserId(authentication), id);
            return afterMutation(authentication, request, model, redirectAttributes,
                    "Đã xóa sản phẩm khỏi giỏ hàng.", false);
        } catch (RuntimeException exception) {
            return afterMutationError(authentication, request, model, redirectAttributes, exception,
                    false);
        }
    }

    @PostMapping({"/cart/items/{id}/increase", "/cart/items/{id}/decrease"})
    public String changeLegacy(Authentication authentication,
                               @PathVariable UUID id,
                               HttpServletRequest request,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        String action = request.getRequestURI().endsWith("/increase") ? "increase" : "decrease";
        return updateInternal(authentication, id, action, request, model, redirectAttributes);
    }

    @PostMapping("/cart/items/{id}")
    public String addLegacy(Authentication authentication,
                            @PathVariable UUID id,
                            @RequestParam(defaultValue = "1") @Min(1) int quantity,
                            @RequestParam(required = false) String customizationText,
                            @RequestParam(required = false) String redirect,
                            HttpServletRequest request,
                            Model model,
                            RedirectAttributes redirectAttributes) {
        try {
            UUID userId = currentUser.requireUserId(authentication);
            cartService.add(userId, id, quantity, customizationText);
            if ("checkout".equalsIgnoreCase(redirect)) {
                WebViewModels.CartView cart = cartService.get(userId);
                WebViewModels.ShopGroupView shop = cart.getShopGroups().stream()
                        .filter(group -> group.getItems().stream()
                                .anyMatch(item -> id.equals(item.getProductId())))
                        .findFirst()
                        .orElseThrow(() -> new com.utewedding.app.exception.BusinessConflictException(
                                "Không tìm thấy sản phẩm trong giỏ hàng. Vui lòng thử lại."));
                if (!shop.isCheckoutAvailable()) {
                    throw new com.utewedding.app.exception.BusinessConflictException(
                            shop.getUnavailableReason() == null
                                    ? "Nhóm sản phẩm này hiện chưa thể thanh toán."
                                    : shop.getUnavailableReason());
                }
                String checkoutUrl = UriComponentsBuilder.fromPath("/checkout")
                        .queryParam("shopId", shop.getShopId())
                        .queryParam("selectionFingerprint", shop.getSelectionFingerprint())
                        .build().encode().toUriString();
                return "redirect:" + checkoutUrl;
            }
            return afterMutation(authentication, request, model, redirectAttributes,
                    "Đã thêm sản phẩm vào giỏ hàng.", false);
        } catch (RuntimeException exception) {
            return afterMutationError(authentication, request, model, redirectAttributes, exception,
                    false);
        }
    }

    private String updateInternal(Authentication authentication, UUID cartItemId, String action,
                                  HttpServletRequest request, Model model,
                                  RedirectAttributes redirectAttributes) {
        try {
            cartService.update(currentUser.requireUserId(authentication), cartItemId, action);
            return afterMutation(authentication, request, model, redirectAttributes,
                    "Đã cập nhật giỏ hàng.", false);
        } catch (RuntimeException exception) {
            return afterMutationError(authentication, request, model, redirectAttributes, exception,
                    false);
        }
    }

    private String afterMutation(Authentication authentication, HttpServletRequest request, Model model,
                                 RedirectAttributes redirectAttributes, String message, boolean checkout) {
        if (isAjax(request)) {
            populateCart(authentication, model);
            model.addAttribute("cartMessage", message);
            model.addAttribute("cartMessageType", "success");
            return "cart/cart :: cartContent";
        }
        redirectAttributes.addFlashAttribute("successMessage", message);
        return checkout ? "redirect:/checkout" : "redirect:/cart";
    }

    private String afterMutationError(Authentication authentication, HttpServletRequest request, Model model,
                                      RedirectAttributes redirectAttributes, RuntimeException exception,
                                      boolean checkout) {
        String message = WebErrorMessages.forException(exception);
        if (isAjax(request)) {
            populateCart(authentication, model);
            model.addAttribute("cartMessage", message);
            model.addAttribute("cartMessageType", "error");
            return "cart/cart :: cartContent";
        }
        redirectAttributes.addFlashAttribute("errorMessage", message);
        return checkout ? "redirect:/checkout" : "redirect:/cart";
    }

    private void populateCart(Authentication authentication, Model model) {
        model.addAttribute("cart", cartService.get(currentUser.requireUserId(authentication)));
        model.addAttribute("groupedCheckoutEnabled", groupedCheckoutEnabled());
        model.addAttribute("checkoutRequest", new WebViewModels.CheckoutForm());
        model.addAttribute("paymentMethods", List.of(WebViewModels.PaymentOption.values()));
    }

    private boolean groupedCheckoutEnabled() {
        return featureFlags != null && featureFlags.isEnabled(
                com.utewedding.app.config.MarketplaceFeatureFlags.Feature.GROUPED_CHECKOUT);
    }

    private boolean isAjax(HttpServletRequest request) {
        return "XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"));
    }
}
