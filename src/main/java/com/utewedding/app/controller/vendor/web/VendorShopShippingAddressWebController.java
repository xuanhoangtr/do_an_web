package com.utewedding.app.controller.vendor.web;

import com.utewedding.app.domain.ShopAddressKind;
import com.utewedding.app.dto.request.ShopShippingAddressForm;
import com.utewedding.app.dto.response.ShopShippingAddressView;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.AdministrativeAddressService;
import com.utewedding.app.service.impl.ShopShippingAddressService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/vendor/store/shipping-addresses")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class VendorShopShippingAddressWebController {
    private final ShopShippingAddressService service;
    private final AdministrativeAddressService addresses;

    public VendorShopShippingAddressWebController(ShopShippingAddressService service,
                                                   AdministrativeAddressService addresses) {
        this.service = service; this.addresses = addresses;
    }

    @GetMapping
    public String page(Authentication authentication, HttpServletResponse response, Model model) {
        noStore(response);
        UserPrincipal actor = actor(authentication);
        Map<ShopAddressKind, ShopShippingAddressView> current = service.currentForVendor(actor);
        model.addAttribute("currentAddresses", current);
        model.addAttribute("provinces", addresses.provinces());
        model.addAttribute("catalogSource", addresses.activeCatalogSource());
        model.addAttribute("pickupForm", form(ShopAddressKind.PICKUP, current));
        model.addAttribute("returnForm", form(ShopAddressKind.RETURN, current));
        return "vendor/store/shipping-addresses";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("addressForm") ShopShippingAddressForm form,
                       BindingResult binding, Authentication authentication, HttpServletResponse response,
                       RedirectAttributes flash) {
        noStore(response);
        try {
            if (binding.hasErrors()) throw new IllegalArgumentException("Vui lòng kiểm tra lại thông tin địa chỉ.");
            service.save(actor(authentication), form);
            flash.addFlashAttribute("successMessage", "Đã lưu địa chỉ thành phiên bản mới. Binding GHN cũ cần được xác minh lại.");
        } catch (AccessDeniedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", userMessage(exception));
        }
        return "redirect:/vendor/store/shipping-addresses";
    }

    private ShopShippingAddressForm form(ShopAddressKind kind,
                                         Map<ShopAddressKind, ShopShippingAddressView> current) {
        ShopShippingAddressForm form = new ShopShippingAddressForm();
        form.setAddressKind(kind);
        form.setCatalogSource(addresses.activeCatalogSource());
        form.setExpectedRevision(current.get(kind) == null ? 0 : current.get(kind).revision());
        if (current.get(kind) != null) {
            var saved = current.get(kind);
            form.setProvinceCode(saved.provinceCode());
            form.setWardCode(saved.wardCode());
            form.setAddressLine(saved.addressLine());
            form.setContactName(saved.contactName());
            form.setContactPhone(saved.contactPhone());
        }
        return form;
    }

    private static UserPrincipal actor(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal) return principal;
        throw new AccessDeniedException("Bạn không có quyền truy cập khu vực cửa hàng.");
    }

    private static String userMessage(RuntimeException exception) {
        if (exception instanceof com.utewedding.app.exception.BusinessConflictException
                && exception.getMessage() != null) return exception.getMessage();
        return "Không thể lưu địa chỉ. Vui lòng tải lại trang và thử lại.";
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
