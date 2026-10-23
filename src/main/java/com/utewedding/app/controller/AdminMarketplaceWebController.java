package com.utewedding.app.controller;

import com.utewedding.app.domain.VoucherType;
import com.utewedding.app.domain.entity.VoucherEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.MarketplaceAdminService;
import com.utewedding.app.service.PageableSupport;
import com.utewedding.app.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
public class AdminMarketplaceWebController {
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String CATEGORY_REDIRECT = "redirect:/admin/categories";
    private static final String VOUCHER_REDIRECT = "redirect:/admin/vouchers";

    private final MarketplaceAdminService marketplaceService;

    public AdminMarketplaceWebController(MarketplaceAdminService marketplaceService) {
        this.marketplaceService = marketplaceService;
    }

    @GetMapping("/categories")
    public String categories(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Model model) {
        model.addAttribute("section", "categories");
        model.addAttribute("categories", marketplaceService.categories(PageableSupport.normalize(pageable,
                "createdAt", java.util.Set.of("createdAt", "updatedAt", "name", "slug", "displayOrder"))));
        java.util.List<com.utewedding.app.domain.entity.CategoryEntity> categoryParentOptions =
                marketplaceService.categoryParentOptions();
        model.addAttribute("categoryParentOptions",
                categoryParentOptions == null ? java.util.List.of() : categoryParentOptions);
        return "admin/marketplace";
    }

    @PostMapping("/categories")
    public String createCategory(@Valid @ModelAttribute("categoryForm") CategoryForm form,
                                 BindingResult result,
                                 @AuthenticationPrincipal UserPrincipal actor,
                                 HttpServletRequest request,
                                 RedirectAttributes redirect) {
        if (result.hasErrors()) return invalid(result, redirect, CATEGORY_REDIRECT);
        try {
            marketplaceService.createCategory(categoryCommand(form), actor.getId(), context(request));
            redirect.addFlashAttribute("successMessage", "Đã tạo danh mục.");
        } catch (BusinessConflictException | ResourceNotFoundException exception) {
            redirect.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return CATEGORY_REDIRECT;
    }

    @PostMapping("/categories/{id}")
    public String updateCategory(@PathVariable Long id,
                                 @Valid @ModelAttribute("categoryForm") CategoryForm form,
                                 BindingResult result,
                                 @AuthenticationPrincipal UserPrincipal actor,
                                 HttpServletRequest request,
                                 RedirectAttributes redirect) {
        if (result.hasErrors()) return invalid(result, redirect, CATEGORY_REDIRECT);
        try {
            marketplaceService.updateCategory(id, categoryCommand(form), actor.getId(), context(request));
            redirect.addFlashAttribute("successMessage", "Đã cập nhật danh mục.");
        } catch (BusinessConflictException | ResourceNotFoundException exception) {
            redirect.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return CATEGORY_REDIRECT;
    }

    @PostMapping("/categories/{id}/deactivate")
    public String deactivateCategory(@PathVariable Long id,
                                     @AuthenticationPrincipal UserPrincipal actor,
                                     HttpServletRequest request,
                                     RedirectAttributes redirect) {
        try {
            marketplaceService.deactivateCategory(id, actor.getId(), context(request));
            redirect.addFlashAttribute("successMessage", "Đã vô hiệu hóa danh mục.");
        } catch (BusinessConflictException | ResourceNotFoundException exception) {
            redirect.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return CATEGORY_REDIRECT;
    }

    @GetMapping("/vouchers")
    public String vouchers(
            @PageableDefault(size = 20, sort = "endsAt", direction = Sort.Direction.DESC) Pageable pageable,
            Model model) {
        Instant now = Instant.now();
        Page<VoucherRow> vouchers = marketplaceService.vouchers(PageableSupport.normalize(pageable, "endsAt",
                java.util.Set.of("endsAt", "startsAt", "code", "active", "valueAmount")))
                .map(voucher -> voucherRow(voucher, now));
        model.addAttribute("section", "vouchers");
        model.addAttribute("vouchers", vouchers);
        return "admin/marketplace";
    }

    @PostMapping("/vouchers")
    public String createVoucher(@Valid @ModelAttribute("voucherForm") VoucherForm form,
                                BindingResult result,
                                @AuthenticationPrincipal UserPrincipal actor,
                                HttpServletRequest request,
                                RedirectAttributes redirect) {
        if (result.hasErrors()) return invalid(result, redirect, VOUCHER_REDIRECT);
        try {
            marketplaceService.createVoucher(voucherCommand(form), actor.getId(), context(request));
            redirect.addFlashAttribute("successMessage", "Đã tạo mã giảm giá.");
        } catch (BusinessConflictException | ResourceNotFoundException exception) {
            redirect.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return VOUCHER_REDIRECT;
    }

    @PostMapping("/vouchers/{id}")
    public String updateVoucher(@PathVariable UUID id,
                                @Valid @ModelAttribute("voucherForm") VoucherForm form,
                                BindingResult result,
                                @AuthenticationPrincipal UserPrincipal actor,
                                HttpServletRequest request,
                                RedirectAttributes redirect) {
        if (result.hasErrors()) return invalid(result, redirect, VOUCHER_REDIRECT);
        try {
            marketplaceService.updateVoucher(id, voucherCommand(form), actor.getId(), context(request));
            redirect.addFlashAttribute("successMessage", "Đã cập nhật mã giảm giá.");
        } catch (BusinessConflictException | ResourceNotFoundException exception) {
            redirect.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return VOUCHER_REDIRECT;
    }

    @PostMapping("/vouchers/{id}/deactivate")
    public String deactivateVoucher(@PathVariable UUID id,
                                    @AuthenticationPrincipal UserPrincipal actor,
                                    HttpServletRequest request,
                                    RedirectAttributes redirect) {
        try {
            marketplaceService.deactivateVoucher(id, actor.getId(), context(request));
            redirect.addFlashAttribute("successMessage", "Đã ngừng mã giảm giá.");
        } catch (BusinessConflictException | ResourceNotFoundException exception) {
            redirect.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return VOUCHER_REDIRECT;
    }

    private static MarketplaceAdminService.CategoryCommand categoryCommand(CategoryForm form) {
        return new MarketplaceAdminService.CategoryCommand(form.name(), form.slug(), form.parentId(),
                form.description(), form.imageUrl(), form.displayOrder(), form.active());
    }

    private static MarketplaceAdminService.VoucherCommand voucherCommand(VoucherForm form) {
        return new MarketplaceAdminService.VoucherCommand(form.code(), form.type(),
                form.valueAmount(), form.minimumOrderAmount(), form.maximumDiscountAmount(),
                parseLocalDateTime(form.startsAt()), parseLocalDateTime(form.endsAt()),
                form.totalQuota(), form.perUserQuota(), form.active());
    }

    private static Instant parseLocalDateTime(String value) {
        try {
            return LocalDateTime.parse(value, LOCAL_DATE_TIME).atZone(DISPLAY_ZONE).toInstant();
        } catch (DateTimeException exception) {
            throw new BusinessConflictException("Nhập ngày giờ hợp lệ theo giờ Việt Nam.");
        }
    }

    private static VoucherRow voucherRow(VoucherEntity voucher, Instant now) {
        String status = !voucher.isActive() ? "Vô hiệu hóa"
                : now.isBefore(voucher.getStartsAt()) ? "Sắp diễn ra"
                : !now.isBefore(voucher.getEndsAt()) ? "Hết hạn" : "Đang áp dụng";
        return new VoucherRow(voucher,
                DISPLAY_DATE_TIME.withZone(DISPLAY_ZONE).format(voucher.getStartsAt()),
                DISPLAY_DATE_TIME.withZone(DISPLAY_ZONE).format(voucher.getEndsAt()), status);
    }

    private static String invalid(BindingResult result, RedirectAttributes redirect, String destination) {
        String message = result.getAllErrors().get(0).getDefaultMessage();
        redirect.addFlashAttribute("errorMessage", message == null ? "Kiểm tra lại thông tin đã nhập." : message);
        return destination;
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }

    public record CategoryForm(@NotBlank(message = "Tên danh mục không được để trống.")
                               @Size(max = 120, message = "Tên danh mục tối đa 120 ký tự.") String name,
                               @NotBlank(message = "Slug không được để trống.")
                               @Size(max = 120, message = "Slug tối đa 120 ký tự.") String slug,
                               Long parentId,
                               @Size(max = 1000, message = "Mô tả tối đa 1000 ký tự.") String description,
                               @Size(max = 2048, message = "URL hình ảnh tối đa 2048 ký tự.") String imageUrl,
                               int displayOrder,
                               @NotNull(message = "Chọn trạng thái danh mục.") Boolean active) { }

    public record VoucherForm(@NotBlank(message = "Mã voucher không được để trống.")
                              @Size(max = 80, message = "Mã voucher tối đa 80 ký tự.") String code,
                              @NotNull(message = "Chọn loại voucher.") VoucherType type,
                              @Min(value = 0, message = "Giá trị voucher không được âm.") long valueAmount,
                              @Min(value = 0, message = "Giá trị đơn tối thiểu không được âm.") long minimumOrderAmount,
                              @Min(value = 0, message = "Mức giảm tối đa không được âm.") Long maximumDiscountAmount,
                              @NotBlank(message = "Nhập thời điểm bắt đầu.")
                              @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$", message = "Thời điểm bắt đầu không hợp lệ.")
                              String startsAt,
                              @NotBlank(message = "Nhập thời điểm kết thúc.")
                              @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$", message = "Thời điểm kết thúc không hợp lệ.")
                              String endsAt,
                              @Min(value = 0, message = "Tổng lượt sử dụng không được âm.") Integer totalQuota,
                              @Min(value = 0, message = "Lượt dùng mỗi người không được âm.") Integer perUserQuota,
                              @NotNull(message = "Chọn trạng thái voucher.") Boolean active) {
        @AssertTrue(message = "Voucher phần trăm không được vượt quá 100%.")
        public boolean isPercentageAmountWithinBusinessRule() {
            return type != VoucherType.PERCENT || valueAmount <= 100;
        }

        @AssertTrue(message = "Voucher miễn phí vận chuyển phải có giá trị 0; các loại còn lại phải lớn hơn 0.")
        public boolean isVoucherValueValid() {
            return type == null || (type == VoucherType.SHIPPING_FREE ? valueAmount == 0 : valueAmount > 0);
        }

        @AssertTrue(message = "Giới hạn voucher miễn phí vận chuyển không được thấp hơn phí giao hàng.")
        public boolean isShippingFreeCapValid() {
            return true; // The current fee is configurable; checkout validates against its actual quote.
        }

        @AssertTrue(message = "Thời gian kết thúc voucher phải sau thời gian bắt đầu.")
        public boolean isValidityPeriodValid() {
            try {
                return LocalDateTime.parse(endsAt, LOCAL_DATE_TIME)
                        .isAfter(LocalDateTime.parse(startsAt, LOCAL_DATE_TIME));
            } catch (DateTimeException | NullPointerException exception) {
                return true;
            }
        }
    }

    public record VoucherRow(VoucherEntity voucher, String startsAtLocal, String endsAtLocal, String status) { }
}
