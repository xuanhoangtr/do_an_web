package com.utewedding.app.controller.vendor.web;

import com.utewedding.app.dto.request.InventoryAdjustmentForm;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.impl.ShopInventoryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
@RequestMapping("/vendor/inventory")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class VendorInventoryWebController {
    private final ShopInventoryService inventory;

    @Autowired
    public VendorInventoryWebController(ShopInventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping({"", "/"})
    public String list(Authentication authentication, HttpServletResponse response,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        noStore(response);
        model.addAttribute("products", inventory.list(actor(authentication), page));
        return "vendor/inventory/index";
    }

    @PostMapping("/{productId}/adjust")
    public String adjust(@PathVariable UUID productId,
                         @Valid @ModelAttribute("adjustmentForm") InventoryAdjustmentForm form,
                         BindingResult binding, Authentication authentication,
                         @RequestParam(defaultValue = "0") int page,
                         HttpServletResponse response, RedirectAttributes flash) {
        noStore(response);
        if (binding.hasErrors()) {
            flash.addFlashAttribute("errorMessage", "Hãy nhập mức điều chỉnh và lý do hợp lệ.");
            return "redirect:/vendor/inventory?page=" + Math.max(0, page);
        }
        try {
            var adjusted = inventory.adjust(actor(authentication), productId, form);
            flash.addFlashAttribute("successMessage", "Đã cập nhật tồn kho của “" + adjusted.name()
                    + "” thành " + adjusted.stockQuantity() + ".");
        } catch (AccessDeniedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", userMessage(exception));
        }
        return "redirect:/vendor/inventory?page=" + Math.max(0, page);
    }

    private static UserPrincipal actor(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal) return principal;
        throw new AccessDeniedException("Bạn không có quyền truy cập khu vực cửa hàng.");
    }

    private static String userMessage(RuntimeException exception) {
        if (exception instanceof BusinessConflictException && exception.getMessage() != null) {
            return exception.getMessage().substring(0, Math.min(280, exception.getMessage().length()));
        }
        if (exception instanceof ResponseStatusException responseStatus && responseStatus.getReason() != null) {
            return responseStatus.getReason().substring(0, Math.min(280, responseStatus.getReason().length()));
        }
        return "Không thể hoàn tất thao tác. Vui lòng thử lại sau.";
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
