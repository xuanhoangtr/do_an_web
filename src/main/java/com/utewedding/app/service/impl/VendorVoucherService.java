package com.utewedding.app.service.impl;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.VoucherFundingSource;
import com.utewedding.app.domain.VoucherScope;
import com.utewedding.app.domain.VoucherType;
import com.utewedding.app.domain.entity.VoucherEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.VoucherRepository;
import com.utewedding.app.repository.VoucherUsageRepository;
import com.utewedding.app.repository.OrderAppliedDiscountRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.service.port.AuditLogPort;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Shop-scoped voucher CRUD. A shop id or funding source is never accepted from the form. */
@Service
public class VendorVoucherService {
    private final MarketplaceFeatureFlags features;
    private final VendorWorkspaceContext workspaceContext;
    private final ShopAccessPolicy accessPolicy;
    private final VoucherRepository vouchers;
    private final VoucherUsageRepository usages;
    private final OrderAppliedDiscountRepository appliedDiscounts;
    private final TextSanitizer sanitizer;
    private final AuditLogPort audit;

    public VendorVoucherService(MarketplaceFeatureFlags features, VendorWorkspaceContext workspaceContext,
                                ShopAccessPolicy accessPolicy, VoucherRepository vouchers,
                                VoucherUsageRepository usages, OrderAppliedDiscountRepository appliedDiscounts,
                                TextSanitizer sanitizer, AuditLogPort audit) {
        this.features = features;
        this.workspaceContext = workspaceContext;
        this.accessPolicy = accessPolicy;
        this.vouchers = vouchers;
        this.usages = usages;
        this.appliedDiscounts = appliedDiscounts;
        this.sanitizer = sanitizer;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<VoucherEntity> list(UserPrincipal actor, Pageable pageable) {
        requireFeature();
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        accessPolicy.requireRead(actor, workspace.shopId());
        return vouchers.findByShopId(workspace.shopId(), pageable);
    }

    @Transactional(readOnly = true)
    public VoucherEntity get(UserPrincipal actor, UUID voucherId) {
        requireFeature();
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        accessPolicy.requireRead(actor, workspace.shopId());
        return vouchers.findByIdAndShopId(voucherId, workspace.shopId()).orElseThrow(VendorVoucherService::notFound);
    }

    @Transactional(rollbackFor = Exception.class)
    public VoucherEntity create(UserPrincipal actor, Command command, RequestContext context) {
        requireFeature();
        validate(command);
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        UUID shopId = accessPolicy.requireMutation(actor, workspace.shopId()).shop().getId();
        String code = normalizeCode(command.code());
        if (vouchers.findByCodeIgnoreCase(code).isPresent()) {
            throw new BusinessConflictException("Mã voucher đã tồn tại.");
        }
        VoucherEntity voucher = new VoucherEntity();
        voucher.setCode(code);
        voucher.setScope(VoucherScope.SHOP);
        voucher.setShopId(shopId);
        voucher.setFundingSource(VoucherFundingSource.SHOP);
        applyTerms(voucher, command);
        try {
            voucher = vouchers.save(voucher);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessConflictException("Mã voucher đã tồn tại hoặc thông tin voucher không hợp lệ.");
        }
        audit.record("SHOP_VOUCHER_CREATED", actor.getId(), "Voucher", voucher.getId(),
                Map.of("shopId", shopId, "type", voucher.getType(), "fundingSource", voucher.getFundingSource(),
                        "active", voucher.isActive()), context);
        return voucher;
    }

    @Transactional(rollbackFor = Exception.class)
    public VoucherEntity update(UserPrincipal actor, UUID voucherId, Command command, RequestContext context) {
        requireFeature();
        validate(command);
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        UUID shopId = accessPolicy.requireMutation(actor, workspace.shopId()).shop().getId();
        VoucherEntity voucher = vouchers.findByIdAndShopIdForUpdate(voucherId, shopId)
                .orElseThrow(VendorVoucherService::notFound);
        if (voucher.getScope() != VoucherScope.SHOP || voucher.getFundingSource() != VoucherFundingSource.SHOP) {
            throw notFound();
        }
        String code = normalizeCode(command.code());
        long activeReservationCount = Math.max(voucher.getUsedCount(), usages.countByVoucherId(voucherId));
        long historicalUseCount = Math.max(activeReservationCount, appliedDiscounts.countByVoucherId(voucherId));
        if (historicalUseCount > 0 && (!code.equalsIgnoreCase(voucher.getCode())
                || command.type() != voucher.getType()
                || command.valueAmount() != voucher.getValueAmount()
                || command.minimumOrderAmount() != voucher.getMinimumOrderAmount()
                || !Objects.equals(command.maximumDiscountAmount(), voucher.getMaximumDiscountAmount()))) {
            throw new BusinessConflictException("Voucher đã được sử dụng nên không thể đổi mã hoặc điều kiện giảm giá.");
        }
        if (vouchers.findByCodeIgnoreCase(code).filter(existing -> !voucherId.equals(existing.getId())).isPresent()) {
            throw new BusinessConflictException("Mã voucher đã tồn tại.");
        }
        if (command.totalQuota() != null && command.totalQuota() < activeReservationCount) {
            throw new BusinessConflictException("Tổng lượt dùng không được thấp hơn số lượt đang được giữ.");
        }
        long maxUsedByOneUser = usages.maxUsageCountByVoucherId(voucherId);
        if (command.perUserQuota() != null && command.perUserQuota() < maxUsedByOneUser) {
            throw new BusinessConflictException("Lượt dùng mỗi người không được thấp hơn số lượt đã sử dụng.");
        }
        Map<String, Object> before = snapshot(voucher);
        voucher.setCode(code);
        applyTerms(voucher, command);
        try {
            voucher = vouchers.save(voucher);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessConflictException("Mã voucher đã tồn tại hoặc thông tin voucher không hợp lệ.");
        }
        audit.record("SHOP_VOUCHER_UPDATED", actor.getId(), "Voucher", voucher.getId(),
                diff(before, snapshot(voucher)), context);
        return voucher;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deactivate(UserPrincipal actor, UUID voucherId, RequestContext context) {
        requireFeature();
        VendorWorkspaceContext.Workspace workspace = workspaceContext.requireWorkspace(actor);
        UUID shopId = accessPolicy.requireMutation(actor, workspace.shopId()).shop().getId();
        VoucherEntity voucher = vouchers.findByIdAndShopIdForUpdate(voucherId, shopId)
                .orElseThrow(VendorVoucherService::notFound);
        if (voucher.getScope() != VoucherScope.SHOP || voucher.getFundingSource() != VoucherFundingSource.SHOP) {
            throw notFound();
        }
        if (!voucher.isActive()) return;
        voucher.setActive(false);
        vouchers.save(voucher);
        audit.record("SHOP_VOUCHER_DEACTIVATED", actor.getId(), "Voucher", voucherId,
                Map.of("shopId", shopId, "active", "true -> false"), context);
    }

    private void requireFeature() {
        if (features == null || !features.isEnabled(Feature.SHOP_VOUCHERS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private void validate(Command command) {
        if (command == null || command.code() == null || command.code().isBlank()
                || command.type() == null || command.startsAt() == null || command.endsAt() == null) {
            throw new BusinessConflictException("Voucher thiếu thông tin bắt buộc.");
        }
        if (command.type() != VoucherType.PERCENT && command.type() != VoucherType.FIXED) {
            throw new BusinessConflictException("Shop chỉ được tạo voucher giảm tiền hàng theo phần trăm hoặc số tiền.");
        }
        if (command.valueAmount() <= 0 || (command.type() == VoucherType.PERCENT && command.valueAmount() > 100)) {
            throw new BusinessConflictException("Giá trị voucher không hợp lệ.");
        }
        if (command.minimumOrderAmount() < 0
                || (command.maximumDiscountAmount() != null && command.maximumDiscountAmount() < 0)
                || (command.totalQuota() != null && command.totalQuota() < 0)
                || (command.perUserQuota() != null && command.perUserQuota() < 0)) {
            throw new BusinessConflictException("Giá trị đơn tối thiểu, giảm tối đa và lượt dùng phải không âm.");
        }
        if (!command.endsAt().isAfter(command.startsAt())) {
            throw new BusinessConflictException("Thời gian kết thúc voucher phải sau thời gian bắt đầu.");
        }
        String normalized = normalizeCode(command.code());
        if (normalized.length() < 3 || normalized.length() > 80) {
            throw new BusinessConflictException("Mã voucher phải từ 3 đến 80 ký tự.");
        }
    }

    private String normalizeCode(String raw) {
        String sanitized = sanitizer.plainText(raw).trim().toUpperCase(java.util.Locale.ROOT);
        if (!sanitized.matches("[A-Z0-9_-]{3,80}")) {
            throw new BusinessConflictException("Mã voucher chỉ được dùng chữ, số, gạch ngang và gạch dưới.");
        }
        return sanitized;
    }

    private static void applyTerms(VoucherEntity voucher, Command command) {
        voucher.setType(command.type());
        voucher.setValueAmount(command.valueAmount());
        voucher.setMinimumOrderAmount(command.minimumOrderAmount());
        voucher.setMaximumDiscountAmount(command.maximumDiscountAmount());
        voucher.setStartsAt(command.startsAt());
        voucher.setEndsAt(command.endsAt());
        voucher.setTotalQuota(command.totalQuota());
        voucher.setPerUserQuota(command.perUserQuota());
        voucher.setActive(Boolean.TRUE.equals(command.active()));
    }

    private static Map<String, Object> snapshot(VoucherEntity value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("shopId", value.getShopId());
        row.put("code", value.getCode());
        row.put("scope", value.getScope());
        row.put("fundingSource", value.getFundingSource());
        row.put("type", value.getType());
        row.put("valueAmount", value.getValueAmount());
        row.put("minimumOrderAmount", value.getMinimumOrderAmount());
        row.put("maximumDiscountAmount", value.getMaximumDiscountAmount());
        row.put("startsAt", value.getStartsAt());
        row.put("endsAt", value.getEndsAt());
        row.put("totalQuota", value.getTotalQuota());
        row.put("perUserQuota", value.getPerUserQuota());
        row.put("active", value.isActive());
        return row;
    }

    private static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        before.forEach((field, value) -> {
            Object next = after.get(field);
            if (!Objects.equals(value, next)) changes.put(field, value + " -> " + next);
        });
        return changes;
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Không tìm thấy voucher của cửa hàng.");
    }

    public record Command(String code, VoucherType type, long valueAmount, long minimumOrderAmount,
                         Long maximumDiscountAmount, Instant startsAt, Instant endsAt,
                         Integer totalQuota, Integer perUserQuota, Boolean active) { }
}
