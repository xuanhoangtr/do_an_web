package com.utewedding.app.controller;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.ShopAddressKind;
import com.utewedding.app.dto.request.ShopShippingAddressForm;
import com.utewedding.app.dto.request.ShippingProviderAccountForm;
import com.utewedding.app.dto.request.ShopShippingBindingForm;
import com.utewedding.app.dto.request.ShopShippingRouteForm;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.dto.response.ShopShippingAddressView;
import com.utewedding.app.service.AdministrativeAddressService;
import com.utewedding.app.service.impl.AdminShopShippingService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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

@Controller
@RequestMapping("/admin/shops/{shopId}/shipping")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminShopShippingWebController {
    private final AdminShopShippingService shipping;
    private final AdministrativeAddressService addresses;
    private final MarketplaceFeatureFlags features;

    public AdminShopShippingWebController(AdminShopShippingService shipping,
                                          AdministrativeAddressService addresses,
                                          MarketplaceFeatureFlags features) {
        this.shipping = shipping; this.addresses = addresses; this.features = features;
    }

    @GetMapping
    public String page(@PathVariable UUID shopId, @AuthenticationPrincipal UserPrincipal actor,
                       HttpServletResponse response, Model model) {
        requireEnabled(); noStore(response);
        var view = shipping.view(actor, shopId);
        model.addAttribute("shipping", view);
        model.addAttribute("accounts", view.accounts());
        model.addAttribute("provinces", addresses.provinces());
        model.addAttribute("catalogSource", addresses.activeCatalogSource());
        model.addAttribute("accountForm", new ShippingProviderAccountForm());
        model.addAttribute("bindingForm", new ShopShippingBindingForm());
        model.addAttribute("routeForm", new ShopShippingRouteForm());
        model.addAttribute("pickupAddressForm", addressForm(ShopAddressKind.PICKUP,
                view.currentAddresses(), null, addresses.activeCatalogSource()));
        model.addAttribute("returnAddressForm", addressForm(ShopAddressKind.RETURN,
                view.currentAddresses(), view.currentAddresses().get("PICKUP"), addresses.activeCatalogSource()));
        return "admin/shops/shipping";
    }

    @PostMapping("/addresses")
    public String saveAddress(@PathVariable UUID shopId,
                              @Valid @ModelAttribute("addressForm") ShopShippingAddressForm form,
                              BindingResult binding, @AuthenticationPrincipal UserPrincipal actor,
                              RedirectAttributes flash) {
        return run(shopId, flash, () -> {
            if (binding.hasErrors()) throw new IllegalArgumentException("Vui lòng kiểm tra lại thông tin địa chỉ.");
            shipping.saveAddress(actor, shopId, form);
        }, "Đã lưu phiên bản địa chỉ mới. Binding GHN cũ cần được xác minh lại.");
    }

    @PostMapping("/accounts")
    public String createAccount(@PathVariable UUID shopId, @Valid @ModelAttribute("accountForm") ShippingProviderAccountForm form,
                                BindingResult binding, @AuthenticationPrincipal UserPrincipal actor,
                                RedirectAttributes flash) {
        return run(shopId, flash, () -> {
            if (binding.hasErrors()) throw new IllegalArgumentException("Tham chiếu cần có dạng credref:...; không nhập token.");
            shipping.createStagingAccount(actor, form);
        }, "Đã thêm tài khoản GHN Staging dưới dạng tham chiếu ngoài hệ thống.");
    }

    @PostMapping("/accounts/{accountId}/verify")
    public String verifyAccount(@PathVariable UUID shopId, @PathVariable UUID accountId,
                                @RequestParam(defaultValue = "false") boolean confirm,
                                @RequestParam String evidence, @AuthenticationPrincipal UserPrincipal actor,
                                RedirectAttributes flash) {
        return run(shopId, flash, () -> shipping.verifyStagingAccount(actor, accountId, confirm, evidence),
                "Đã ghi nhận kết quả kiểm tra tài khoản GHN Staging.");
    }

    @PostMapping("/bindings")
    public String createBinding(@PathVariable UUID shopId,
                                @Valid @ModelAttribute("bindingForm") ShopShippingBindingForm form,
                                BindingResult binding, @AuthenticationPrincipal UserPrincipal actor,
                                RedirectAttributes flash) {
        return run(shopId, flash, () -> {
            if (binding.hasErrors()) throw new IllegalArgumentException("Thông tin tài khoản, ShopId hoặc chế độ tuyến GHN không hợp lệ.");
            shipping.createBinding(actor, shopId, form);
        }, "API_QUOTE".equalsIgnoreCase(form.getRouteMode())
                ? "Đã tạo binding GHN API Quote mới ở trạng thái chờ xác minh."
                : "Đã tạo binding mới ở trạng thái chờ xác minh.");
    }

    @PostMapping("/bindings/{bindingId}/routes")
    public String addRoute(@PathVariable UUID shopId, @PathVariable UUID bindingId,
                           @Valid @ModelAttribute("routeForm") ShopShippingRouteForm form,
                           BindingResult binding, @AuthenticationPrincipal UserPrincipal actor,
                           RedirectAttributes flash) {
        return run(shopId, flash, () -> {
            if (binding.hasErrors()) throw new IllegalArgumentException("Tuyến địa chỉ không hợp lệ.");
            shipping.addRoute(actor, shopId, bindingId, form);
        }, "Đã thêm tuyến ở trạng thái chờ xác minh.");
    }

    @PostMapping("/bindings/{bindingId}/routes/{routeId}/verify")
    public String verifyRoute(@PathVariable UUID shopId, @PathVariable UUID bindingId, @PathVariable UUID routeId,
                              @RequestParam(defaultValue = "false") boolean confirmCoverage,
                              @RequestParam String evidence, @AuthenticationPrincipal UserPrincipal actor,
                              RedirectAttributes flash) {
        return run(shopId, flash, () -> shipping.verifyRoute(actor, shopId, bindingId, routeId,
                confirmCoverage, evidence), "Đã xác minh tuyến GHN Staging.");
    }

    @PostMapping("/bindings/{bindingId}/verify")
    public String verifyBinding(@PathVariable UUID shopId, @PathVariable UUID bindingId,
                                @RequestParam(defaultValue = "false") boolean confirmAddressAndCoverage,
                                @RequestParam String evidence, @AuthenticationPrincipal UserPrincipal actor,
                                RedirectAttributes flash) {
        return run(shopId, flash, () -> shipping.verifyBinding(actor, shopId, bindingId,
                confirmAddressAndCoverage, evidence),
                "Đã xác minh binding GHN Staging. COD vẫn bị khóa cho đến khi xác minh riêng bên nhận tiền.");
    }

    @PostMapping("/bindings/{bindingId}/verify-cod")
    public String verifyCod(@PathVariable UUID shopId, @PathVariable UUID bindingId,
                            @RequestParam(defaultValue = "false") boolean confirm,
                            @RequestParam String evidence, @AuthenticationPrincipal UserPrincipal actor,
                            RedirectAttributes flash) {
        return run(shopId, flash, () -> shipping.verifyCodBeneficiary(actor, shopId, bindingId, confirm, evidence),
                "Đã ghi nhận xác minh riêng bên nhận tiền COD.");
    }

    private String run(UUID shopId, RedirectAttributes flash, Runnable action, String success) {
        requireEnabled();
        try { action.run(); flash.addFlashAttribute("successMessage", success); }
        catch (RuntimeException exception) { flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception)); }
        return "redirect:/admin/shops/" + shopId + "/shipping";
    }

    private void requireEnabled() {
        if (features == null || !features.isEnabled(Feature.VENDOR_MANAGEMENT)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    private static ShopShippingAddressForm addressForm(ShopAddressKind kind,
                                                        java.util.Map<String, ShopShippingAddressView> current,
                                                        ShopShippingAddressView fallback,
                                                        String catalogSource) {
        ShopShippingAddressView saved = current.getOrDefault(kind.name(), fallback);
        ShopShippingAddressForm form = new ShopShippingAddressForm();
        form.setAddressKind(kind);
        form.setCatalogSource(catalogSource);
        form.setExpectedRevision(current.get(kind.name()) == null ? 0 : current.get(kind.name()).revision());
        if (saved != null) {
            form.setProvinceCode(saved.provinceCode());
            form.setWardCode(saved.wardCode());
            form.setAddressLine(saved.addressLine());
            form.setContactName(saved.contactName());
            form.setContactPhone(saved.contactPhone());
        }
        return form;
    }
}
