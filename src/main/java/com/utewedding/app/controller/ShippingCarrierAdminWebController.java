package com.utewedding.app.controller;

import com.utewedding.app.domain.entity.ShippingCarrierEntity;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.ShippingCarrierAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/shipping-carriers")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN','SUPER_ADMIN')")
public class ShippingCarrierAdminWebController {
    private final ShippingCarrierAdminService service;

    public ShippingCarrierAdminWebController(ShippingCarrierAdminService service) {
        this.service = service;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "") String scope,
                       @RequestParam(defaultValue = "") String provider,
                       @RequestParam(defaultValue = "") String state,
                       @RequestParam(defaultValue = "0") int page,
                       @AuthenticationPrincipal UserPrincipal actor,
                       Model model) {
        int pageNumber = Math.max(0, page);
        var carriers = service.search(q, scope, provider, state,
                PageRequest.of(pageNumber, 20,
                        Sort.by(Sort.Order.asc("coverageScope"), Sort.Order.asc("code"))), actor.getRole().name());
        if (pageNumber > 0 && carriers.getTotalPages() > 0 && pageNumber >= carriers.getTotalPages()) {
            pageNumber = carriers.getTotalPages() - 1;
            carriers = service.search(q, scope, provider, state,
                    PageRequest.of(pageNumber, 20,
                            Sort.by(Sort.Order.asc("coverageScope"), Sort.Order.asc("code"))), actor.getRole().name());
        }
        model.addAttribute("carriers", carriers);
        model.addAttribute("q", q);
        model.addAttribute("scope", scope);
        model.addAttribute("provider", provider);
        model.addAttribute("state", state);
        model.addAttribute("page", pageNumber);
        return "admin/shipping-carriers";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute CreateForm form,
                         BindingResult binding, @AuthenticationPrincipal UserPrincipal actor,
                         HttpServletRequest request, RedirectAttributes flash) {
        if (binding.hasErrors()) {
            flash.addFlashAttribute("errorMessage", binding.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/admin/shipping-carriers";
        }
        String[] route = form.route() == null ? new String[0] : form.route().split(":", 2);
        if (route.length != 2) {
            flash.addFlashAttribute("errorMessage", "Tuyến vận chuyển không hợp lệ.");
            return "redirect:/admin/shipping-carriers";
        }
        return run(flash, () -> service.create(new ShippingCarrierAdminService.CreateCommand(
                        form.code(), form.displayName(), form.description(), route[0],
                        route[1], form.customerFee(), form.active(), form.defaultForNewOrders()),
                actor.getId(), context(actor, request)), "Đã tạo cấu hình nhà vận chuyển.");
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable UUID id, @RequestParam long version,
                         @Valid @ModelAttribute UpdateForm form,
                         BindingResult binding, @AuthenticationPrincipal UserPrincipal actor,
                         HttpServletRequest request, RedirectAttributes flash) {
        if (binding.hasErrors()) {
            flash.addFlashAttribute("errorMessage", binding.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/admin/shipping-carriers";
        }
        return run(flash, () -> service.update(id, version,
                        new ShippingCarrierAdminService.UpdateCommand(form.displayName(), form.description(), form.customerFee()),
                        actor.getId(), context(actor, request)), "Đã cập nhật cấu hình nhà vận chuyển.");
    }

    @PostMapping("/{id}/default")
    public String setDefault(@PathVariable UUID id, @RequestParam long version,
                             @AuthenticationPrincipal UserPrincipal actor,
                             HttpServletRequest request, RedirectAttributes flash) {
        return run(flash, () -> service.setDefault(id, version, actor.getId(), context(actor, request)),
                "Đã đổi nhà vận chuyển mặc định cho phạm vi này.");
    }

    @PostMapping("/{id}/active")
    public String active(@PathVariable UUID id, @RequestParam long version, @RequestParam boolean active,
                         @AuthenticationPrincipal UserPrincipal actor,
                         HttpServletRequest request, RedirectAttributes flash) {
        return run(flash, () -> service.setActive(id, version, active, actor.getId(), context(actor, request)),
                active ? "Đã bật cấu hình nhà vận chuyển." : "Đã ngừng cấu hình nhà vận chuyển.");
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, @RequestParam long version,
                         @AuthenticationPrincipal UserPrincipal actor,
                         HttpServletRequest request, RedirectAttributes flash) {
        return run(flash, () -> service.softDelete(id, version, actor.getId(), context(actor, request)),
                "Đã xóa mềm cấu hình nhà vận chuyển.");
    }

    @PostMapping("/{id}/restore")
    public String restore(@PathVariable UUID id, @RequestParam long version,
                          @AuthenticationPrincipal UserPrincipal actor,
                          HttpServletRequest request, RedirectAttributes flash) {
        return run(flash, () -> service.restore(id, version, actor.getId(), context(actor, request)),
                "Đã khôi phục cấu hình ở trạng thái ngừng hoạt động.");
    }

    private String run(RedirectAttributes flash, Runnable action, String success) {
        try {
            action.run();
            flash.addFlashAttribute("successMessage", success);
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/admin/shipping-carriers";
    }

    private RequestContext context(UserPrincipal actor, HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-ID"), actor.getRole().name());
    }

    public record CreateForm(@NotBlank(message = "Nhập mã cấu hình.")
                             @Size(max = 60, message = "Mã cấu hình tối đa 60 ký tự.")
                             @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{1,59}", message = "Mã cấu hình không hợp lệ.")
                             String code,
                             @NotBlank(message = "Nhập tên hiển thị.") @Size(max = 120, message = "Tên hiển thị tối đa 120 ký tự.")
                             String displayName,
                             @Size(max = 500, message = "Mô tả tối đa 500 ký tự.") String description,
                             @NotBlank(message = "Chọn tuyến vận chuyển.") String route,
                             @Min(value = 0, message = "Phí khách trả không được âm.")
                             @Max(value = 1_000_000_000_000L, message = "Phí khách trả vượt giới hạn.") long customerFee,
                             boolean active, boolean defaultForNewOrders) { }

    public record UpdateForm(@NotBlank(message = "Nhập tên hiển thị.")
                             @Size(max = 120, message = "Tên hiển thị tối đa 120 ký tự.") String displayName,
                             @Size(max = 500, message = "Mô tả tối đa 500 ký tự.") String description,
                             @Min(value = 0, message = "Phí khách trả không được âm.")
                             @Max(value = 1_000_000_000_000L, message = "Phí khách trả vượt giới hạn.") long customerFee) { }
}
