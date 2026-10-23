package com.utewedding.app.controller;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.ProductModerationStatus;
import com.utewedding.app.dto.request.ProductModerationForm;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.AdminProductModerationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
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
@RequestMapping("/admin/products/moderation")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminProductModerationWebController {
    private final AdminProductModerationService moderation;
    private final MarketplaceFeatureFlags features;

    public AdminProductModerationWebController(AdminProductModerationService moderation,
                                               MarketplaceFeatureFlags features) {
        this.moderation = moderation;
        this.features = features;
    }

    @GetMapping({"", "/"})
    public String list(@AuthenticationPrincipal UserPrincipal actor,
                       @RequestParam(defaultValue = "PENDING") ProductModerationStatus status,
                       @RequestParam(defaultValue = "0") int page,
                       HttpServletResponse response, Model model) {
        requireEnabled();
        noStore(response);
        model.addAttribute("products", moderation.list(actor.getId(), status, page));
        model.addAttribute("selectedStatus", status);
        model.addAttribute("statuses", List.of(ProductModerationStatus.PENDING,
                ProductModerationStatus.REJECTED, ProductModerationStatus.BLOCKED,
                ProductModerationStatus.APPROVED));
        model.addAttribute("statusLabels", statusLabels());
        return "admin/products/moderation/index";
    }

    @GetMapping("/{productId}")
    public String detail(@PathVariable UUID productId, @AuthenticationPrincipal UserPrincipal actor,
                         HttpServletResponse response, Model model) {
        requireEnabled();
        noStore(response);
        model.addAttribute("detail", moderation.detail(actor.getId(), productId));
        model.addAttribute("moderationForm", new ProductModerationForm());
        model.addAttribute("statusLabels", statusLabels());
        return "admin/products/moderation/detail";
    }

    @PostMapping("/{productId}/{decision}")
    public String decide(@PathVariable UUID productId,
                         @PathVariable ProductModerationStatus decision,
                         @Valid @ModelAttribute("moderationForm") ProductModerationForm form,
                         BindingResult binding, @AuthenticationPrincipal UserPrincipal actor,
                         HttpServletResponse response, HttpServletRequest request,
                         RedirectAttributes flash, Model model) {
        requireEnabled();
        noStore(response);
        if (binding.hasErrors()) {
            model.addAttribute("detail", moderation.detail(actor.getId(), productId));
            model.addAttribute("statusLabels", statusLabels());
            model.addAttribute("formError", "Lý do tối đa 1000 ký tự.");
            return "admin/products/moderation/detail";
        }
        try {
            moderation.decide(actor.getId(), productId, decision, form.getReason(), context(request));
            flash.addFlashAttribute("successMessage", "Đã lưu quyết định kiểm duyệt sản phẩm.");
            return "redirect:/admin/products/moderation/" + productId;
        } catch (AccessDeniedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            model.addAttribute("detail", moderation.detail(actor.getId(), productId));
            model.addAttribute("moderationForm", form);
            model.addAttribute("statusLabels", statusLabels());
            model.addAttribute("formError", userMessage(exception));
            return "admin/products/moderation/detail";
        }
    }

    private void requireEnabled() {
        if (!features.isEnabled(Feature.VENDOR_MANAGEMENT)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
    }

    private static RequestContext context(HttpServletRequest request) {
        UserPrincipal actor = (UserPrincipal) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-ID"), actor.getRole().name());
    }

    private static Map<ProductModerationStatus, String> statusLabels() {
        return Map.of(ProductModerationStatus.PENDING, "Chờ duyệt",
                ProductModerationStatus.APPROVED, "Đã duyệt",
                ProductModerationStatus.REJECTED, "Từ chối",
                ProductModerationStatus.BLOCKED, "Đang bị chặn");
    }

    private static String userMessage(RuntimeException exception) {
        if (exception instanceof BusinessConflictException && exception.getMessage() != null) {
            return exception.getMessage().substring(0, Math.min(280, exception.getMessage().length()));
        }
        if (exception instanceof ResponseStatusException status && status.getReason() != null) {
            return status.getReason().substring(0, Math.min(280, status.getReason().length()));
        }
        return "Không thể hoàn tất thao tác. Vui lòng thử lại sau.";
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
