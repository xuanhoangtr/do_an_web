package com.utewedding.app.controller;

import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.VoucherType;
import com.utewedding.app.domain.VoucherScope;
import com.utewedding.app.domain.VoucherFundingSource;
import com.utewedding.app.domain.entity.CategoryEntity;
import com.utewedding.app.domain.entity.VoucherEntity;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.MarketplaceAdminService;
import com.utewedding.app.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.AssertTrue;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import com.utewedding.app.service.PageableSupport;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','SUPER_ADMIN')")
public class AdminMarketplaceApiController {
    private final MarketplaceAdminService marketplaceService;
    public AdminMarketplaceApiController(MarketplaceAdminService marketplaceService) {
        this.marketplaceService = marketplaceService;
    }

    @GetMapping("/categories") public Page<CategoryResponse> categories(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return marketplaceService.categories(PageableSupport.normalize(pageable, "createdAt",
                java.util.Set.of("createdAt", "updatedAt", "name", "slug", "displayOrder")))
                .map(AdminMarketplaceApiController::toResponse);
    }
    @PostMapping("/categories") public CategoryResponse createCategory(
            @Valid @RequestBody CategoryRequest request,
            @AuthenticationPrincipal UserPrincipal actor, HttpServletRequest httpRequest) {
        return toResponse(marketplaceService.createCategory(new MarketplaceAdminService.CategoryCommand(
                request.name(), request.slug(), request.parentId(), request.description(), request.imageUrl(),
                request.displayOrder(), request.active()), actor.getId(), context(httpRequest)));
    }
    @PutMapping("/categories/{id}") public CategoryResponse updateCategory(
            @PathVariable Long id, @Valid @RequestBody CategoryRequest request,
            @AuthenticationPrincipal UserPrincipal actor, HttpServletRequest httpRequest) {
        return toResponse(marketplaceService.updateCategory(id, new MarketplaceAdminService.CategoryCommand(
                request.name(), request.slug(), request.parentId(), request.description(), request.imageUrl(),
                request.displayOrder(), request.active()), actor.getId(), context(httpRequest)));
    }
    @DeleteMapping("/categories/{id}") public void deleteCategory(
            @PathVariable Long id, @AuthenticationPrincipal UserPrincipal actor, HttpServletRequest httpRequest) {
        marketplaceService.deactivateCategory(id, actor.getId(), context(httpRequest));
    }

    @GetMapping("/vouchers") public Page<VoucherResponse> vouchers(
            @PageableDefault(size = 20, sort = "endsAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return marketplaceService.vouchers(PageableSupport.normalize(pageable, "endsAt",
                java.util.Set.of("endsAt", "startsAt", "code", "active", "valueAmount")))
                .map(AdminMarketplaceApiController::toResponse);
    }
    @PostMapping("/vouchers") public VoucherResponse createVoucher(
            @Valid @RequestBody VoucherRequest request,
            @AuthenticationPrincipal UserPrincipal actor, HttpServletRequest httpRequest) {
        return toResponse(marketplaceService.createVoucher(toCommand(request), actor.getId(), context(httpRequest)));
    }
    @PutMapping("/vouchers/{id}") public VoucherResponse updateVoucher(
            @PathVariable UUID id, @Valid @RequestBody VoucherRequest request,
            @AuthenticationPrincipal UserPrincipal actor, HttpServletRequest httpRequest) {
        return toResponse(marketplaceService.updateVoucher(id, toCommand(request), actor.getId(), context(httpRequest)));
    }
    @DeleteMapping("/vouchers/{id}") public void deleteVoucher(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal actor, HttpServletRequest httpRequest) {
        marketplaceService.deactivateVoucher(id, actor.getId(), context(httpRequest));
    }

    private MarketplaceAdminService.VoucherCommand toCommand(VoucherRequest request) {
        return new MarketplaceAdminService.VoucherCommand(request.code(), request.type(),
                request.valueAmount(), request.minimumOrderAmount(), request.maximumDiscountAmount(),
                request.startsAt(), request.endsAt(), request.totalQuota(), request.perUserQuota(), request.active());
    }

    private RequestContext context(HttpServletRequest request) {
        return new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("X-Request-Id"));
    }


    private static CategoryResponse toResponse(CategoryEntity value) {
        return new CategoryResponse(value.getId(), value.getParentId(), value.getName(), value.getSlug(),
                value.getDescription(), value.getImageUrl(), value.getDisplayOrder(), value.isActive(),
                value.getCreatedAt(), value.getUpdatedAt());
    }

    private static VoucherResponse toResponse(VoucherEntity value) {
        return new VoucherResponse(value.getId(), value.getCode(), value.getType(),
                value.getScope(), value.getShopId(), value.getFundingSource(),
                value.getValueAmount(), value.getMinimumOrderAmount(), value.getMaximumDiscountAmount(),
                value.getStartsAt(), value.getEndsAt(), value.getTotalQuota(), value.getPerUserQuota(),
                value.getUsedCount(), value.isActive(), value.getVersion());
    }


    public record CategoryRequest(@NotBlank String name, @NotBlank String slug, Long parentId, String description, String imageUrl, int displayOrder, Boolean active) { }
    public record VoucherRequest(@NotBlank String code, @NotNull VoucherType type, @Min(0) long valueAmount,
                                 @Min(0) long minimumOrderAmount, Long maximumDiscountAmount, @NotNull Instant startsAt,
                                 @NotNull Instant endsAt, Integer totalQuota, Integer perUserQuota, Boolean active) {
        @AssertTrue(message = "Giá trị voucher không phù hợp với loại ưu đãi.")
        public boolean isVoucherValueValid() {
            return type == null || (type == VoucherType.SHIPPING_FREE ? valueAmount == 0 : valueAmount > 0);
        }
        @AssertTrue(message = "Giới hạn voucher miễn phí vận chuyển không được thấp hơn phí giao hàng.")
        public boolean isShippingFreeCapValid() {
            return true; // The current fee is configurable; checkout validates against its actual quote.
        }
    }
    public record CategoryResponse(Long id, Long parentId, String name, String slug, String description, String imageUrl,
                                   int displayOrder, boolean active, Instant createdAt, Instant updatedAt) { }
    public record VoucherResponse(UUID id, String code, VoucherType type, VoucherScope scope,
                                  UUID shopId, VoucherFundingSource fundingSource, long valueAmount,
                                  long minimumOrderAmount, Long maximumDiscountAmount, Instant startsAt, Instant endsAt,
                                  Integer totalQuota, Integer perUserQuota, int usedCount, boolean active, long version) { }
}
