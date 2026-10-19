package com.utewedding.app.controller.vendor.web;

import com.utewedding.app.domain.VoucherType;
import com.utewedding.app.domain.entity.VoucherEntity;
import com.utewedding.app.dto.request.VendorVoucherForm;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.PageableSupport;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.VendorVoucherService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

/** Server-rendered shop voucher management. Funding and shop scope come only from the checked owner workspace. */
@Controller
@RequestMapping("/vendor/vouchers")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class VendorVoucherWebController {
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter LOCAL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final VendorVoucherService vouchers;

    public VendorVoucherWebController(VendorVoucherService vouchers) {
        this.vouchers = vouchers;
    }

    @GetMapping({"", "/"})
    public String list(Pageable pageable, Authentication authentication, HttpServletResponse response, Model model) {
        noStore(response);
        Page<VoucherEntity> page = vouchers.list(actor(authentication), PageableSupport.normalize(pageable,
                "endsAt", Set.of("code", "startsAt", "endsAt", "active", "valueAmount")));
        Instant now = Instant.now();
        model.addAttribute("vouchers", page.map(value -> row(value, now)));
        model.addAttribute("voucherForm", blankForm());
        model.addAttribute("percentType", VoucherType.PERCENT);
        model.addAttribute("fixedType", VoucherType.FIXED);
        return "vendor/vouchers/index";
    }

    @GetMapping("/{voucherId}/edit")
    public String edit(@PathVariable UUID voucherId, Authentication authentication,
                       HttpServletResponse response, Model model) {
        noStore(response);
        VoucherEntity voucher = vouchers.get(actor(authentication), voucherId);
        model.addAttribute("voucher", voucher);
        model.addAttribute("voucherForm", form(voucher));
        model.addAttribute("percentType", VoucherType.PERCENT);
        model.addAttribute("fixedType", VoucherType.FIXED);
        return "vendor/vouchers/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("voucherForm") VendorVoucherForm form,
                         BindingResult binding, Authentication authentication, HttpServletRequest request,
                         HttpServletResponse response, Model model, RedirectAttributes flash) {
        noStore(response);
        UserPrincipal principal = actor(authentication);
        if (binding.hasErrors()) {
            flash.addFlashAttribute("errorMessage", binding.getAllErrors().getFirst().getDefaultMessage());
            return "redirect:/vendor/vouchers";
        }
        try {
            vouchers.create(principal, command(form), context(request));
            flash.addFlashAttribute("successMessage", "Đã tạo voucher cho cửa hàng.");
            return "redirect:/vendor/vouchers";
        } catch (AccessDeniedException | ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", userMessage(exception));
            return "redirect:/vendor/vouchers";
        }
    }

    @PostMapping("/{voucherId}")
    public String update(@PathVariable UUID voucherId,
                         @Valid @ModelAttribute("voucherForm") VendorVoucherForm form,
                         BindingResult binding, Authentication authentication, HttpServletRequest request,
                         HttpServletResponse response,
                         Model model, RedirectAttributes flash) {
        noStore(response);
        UserPrincipal principal = actor(authentication);
        if (binding.hasErrors()) {
            model.addAttribute("voucher", safeGet(principal, voucherId));
            model.addAttribute("percentType", VoucherType.PERCENT);
            model.addAttribute("fixedType", VoucherType.FIXED);
            model.addAttribute("formError", binding.getAllErrors().getFirst().getDefaultMessage());
            return "vendor/vouchers/form";
        }
        try {
            vouchers.update(principal, voucherId, command(form), context(request));
            flash.addFlashAttribute("successMessage", "Đã cập nhật voucher.");
            return "redirect:/vendor/vouchers";
        } catch (AccessDeniedException | ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            model.addAttribute("voucher", safeGet(principal, voucherId));
            model.addAttribute("percentType", VoucherType.PERCENT);
            model.addAttribute("fixedType", VoucherType.FIXED);
            model.addAttribute("formError", userMessage(exception));
            return "vendor/vouchers/form";
        }
    }

    @PostMapping("/{voucherId}/deactivate")
    public String deactivate(@PathVariable UUID voucherId, Authentication authentication,
                             HttpServletRequest request, HttpServletResponse response, RedirectAttributes flash) {
        noStore(response);
        try {
            vouchers.deactivate(actor(authentication), voucherId, context(request));
            flash.addFlashAttribute("successMessage", "Đã ngừng voucher. Lịch sử sử dụng vẫn được giữ.");
        } catch (AccessDeniedException | ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            flash.addFlashAttribute("errorMessage", userMessage(exception));
        }
        return "redirect:/vendor/vouchers";
    }

    private VoucherEntity safeGet(UserPrincipal actor, UUID id) {
        try { return vouchers.get(actor, id); }
        catch (RuntimeException exception) { return null; }
    }

    private static VendorVoucherForm blankForm() {
        return new VendorVoucherForm("", VoucherType.FIXED, 1, 0, null,
                LOCAL_FORMAT.withZone(DISPLAY_ZONE).format(Instant.now()),
                LOCAL_FORMAT.withZone(DISPLAY_ZONE).format(Instant.now().plusSeconds(30L * 24 * 60 * 60)),
                null, null, true);
    }

    private static VendorVoucherForm form(VoucherEntity voucher) {
        return new VendorVoucherForm(voucher.getCode(), voucher.getType(), voucher.getValueAmount(),
                voucher.getMinimumOrderAmount(), voucher.getMaximumDiscountAmount(),
                LOCAL_FORMAT.withZone(DISPLAY_ZONE).format(voucher.getStartsAt()),
                LOCAL_FORMAT.withZone(DISPLAY_ZONE).format(voucher.getEndsAt()),
                voucher.getTotalQuota(), voucher.getPerUserQuota(), voucher.isActive());
    }

    private static VendorVoucherService.Command command(VendorVoucherForm form) {
        return new VendorVoucherService.Command(form.code(), form.type(), form.valueAmount(),
                form.minimumOrderAmount(), form.maximumDiscountAmount(), parse(form.startsAt()), parse(form.endsAt()),
                form.totalQuota(), form.perUserQuota(), form.active());
    }

    private static Instant parse(String value) {
        try { return LocalDateTime.parse(value, LOCAL_FORMAT).atZone(DISPLAY_ZONE).toInstant(); }
        catch (DateTimeException exception) { throw new BusinessConflictException("Nhập ngày giờ hợp lệ theo giờ Việt Nam."); }
    }

    private static VoucherRow row(VoucherEntity voucher, Instant now) {
        String status = !voucher.isActive() ? "Đã tắt"
                : now.isBefore(voucher.getStartsAt()) ? "Sắp áp dụng"
                : !now.isBefore(voucher.getEndsAt()) ? "Đã hết hạn" : "Đang áp dụng";
        return new VoucherRow(voucher,
                DISPLAY_FORMAT.withZone(DISPLAY_ZONE).format(voucher.getStartsAt()),
                DISPLAY_FORMAT.withZone(DISPLAY_ZONE).format(voucher.getEndsAt()), status);
    }

    private static RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }

    private static UserPrincipal actor(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal) return principal;
        throw new AccessDeniedException("Bạn không có quyền quản lý voucher cửa hàng.");
    }

    private static String userMessage(RuntimeException exception) {
        if (exception instanceof BusinessConflictException && exception.getMessage() != null) {
            return exception.getMessage().substring(0, Math.min(280, exception.getMessage().length()));
        }
        return "Không thể hoàn tất thao tác. Vui lòng thử lại sau.";
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    public record VoucherRow(VoucherEntity voucher, String startsAtLocal, String endsAtLocal, String status) { }
}
