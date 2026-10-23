package com.utewedding.app.service.impl;

import com.utewedding.app.domain.VoucherType;
import com.utewedding.app.domain.VoucherScope;
import com.utewedding.app.domain.VoucherFundingSource;
import com.utewedding.app.domain.entity.CategoryEntity;
import com.utewedding.app.domain.entity.VoucherEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.CatalogCategoryRepository;
import com.utewedding.app.repository.OrderAppliedDiscountRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.VoucherRepository;
import com.utewedding.app.repository.VoucherUsageRepository;
import com.utewedding.app.service.MarketplaceAdminService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.service.port.AuditLogPort;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketplaceAdminServiceImpl implements MarketplaceAdminService {
    private final CatalogCategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherUsageRepository voucherUsageRepository;
    private final OrderAppliedDiscountRepository appliedDiscounts;
    private final AuditLogPort auditLog;
    private final TextSanitizer sanitizer;

    @Autowired
    public MarketplaceAdminServiceImpl(CatalogCategoryRepository categoryRepository,
                                       ProductRepository productRepository,
                                       VoucherRepository voucherRepository,
                                       VoucherUsageRepository voucherUsageRepository,
                                       OrderAppliedDiscountRepository appliedDiscounts,
                                       AuditLogPort auditLog,
                                       TextSanitizer sanitizer) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.voucherRepository = voucherRepository;
        this.voucherUsageRepository = voucherUsageRepository;
        this.appliedDiscounts = appliedDiscounts;
        this.auditLog = auditLog;
        this.sanitizer = sanitizer;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CategoryEntity> categories(Pageable pageable) {
        return categoryRepository.findAll(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryEntity> categoryParentOptions() {
        return categoryRepository.findAll(Sort.by("displayOrder").ascending().and(Sort.by("name").ascending()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryEntity createCategory(CategoryCommand command, UUID actorId, RequestContext context) {
        requireActor(actorId);
        CategoryEntity category = new CategoryEntity(normalizeRequired(command.name(), "Tên danh mục"),
                normalizeRequired(command.slug(), "Slug danh mục"));
        applyCategory(category, command);
        ensureUniqueCategory(category, null);
        try {
            category = categoryRepository.save(category);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessConflictException("Tên hoặc slug danh mục đã tồn tại.");
        }
        auditLog.recordTarget("CATEGORY_CREATED", actorId, "Category", String.valueOf(category.getId()),
                Map.of("active", category.isActive()), context);
        return category;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryEntity updateCategory(Long id, CategoryCommand command, UUID actorId, RequestContext context) {
        requireActor(actorId);
        CategoryEntity category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy danh mục."));
        CategoryAuditSnapshot before = categorySnapshot(category);
        String name = normalizeRequired(command.name(), "Tên danh mục");
        String slug = normalizeRequired(command.slug(), "Slug danh mục");
        category.setName(name);
        category.setSlug(slug);
        applyCategory(category, command);
        if (!category.isActive() && productRepository.countByCategoryIdAndActiveTrue(id) > 0) {
            throw new BusinessConflictException("Không thể vô hiệu hóa danh mục đang có sản phẩm hoạt động.");
        }
        ensureUniqueCategory(category, id);
        try {
            category = categoryRepository.save(category);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessConflictException("Tên hoặc slug danh mục đã tồn tại.");
        }
        auditLog.recordTarget("CATEGORY_UPDATED", actorId, "Category", String.valueOf(id),
                categoryChanges(before, category), context);
        return category;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deactivateCategory(Long id, UUID actorId, RequestContext context) {
        requireActor(actorId);
        CategoryEntity category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy danh mục."));
        if (productRepository.countByCategoryIdAndActiveTrue(id) > 0) {
            throw new BusinessConflictException("Không thể vô hiệu hóa danh mục đang có sản phẩm hoạt động.");
        }
        boolean previousActive = category.isActive();
        category.setActive(false);
        categoryRepository.save(category);
        auditLog.recordTarget("CATEGORY_DEACTIVATED", actorId, "Category", String.valueOf(id),
                Map.of("active", previousActive + " -> false"), context);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VoucherEntity> vouchers(Pageable pageable) {
        return voucherRepository.findAll(pageable);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VoucherEntity createVoucher(VoucherCommand command, UUID actorId, RequestContext context) {
        requireActor(actorId);
        validateVoucher(command);
        String code = normalizeRequired(command.code(), "Mã voucher").toUpperCase(java.util.Locale.ROOT);
        if (voucherRepository.findByCodeIgnoreCase(code).isPresent()) {
            throw new BusinessConflictException("Mã voucher đã tồn tại.");
        }
        VoucherEntity voucher = new VoucherEntity();
        voucher.setScope(VoucherScope.PLATFORM);
        voucher.setShopId(null);
        voucher.setFundingSource(VoucherFundingSource.PLATFORM);
        applyVoucher(voucher, command, code);
        try {
            voucher = voucherRepository.save(voucher);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessConflictException("Mã voucher đã tồn tại.");
        }
        auditLog.record("VOUCHER_CREATED", actorId, "Voucher", voucher.getId(),
                Map.of("type", voucher.getType(), "valueAmount", voucher.getValueAmount(),
                        "active", voucher.isActive()), context);
        return voucher;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VoucherEntity updateVoucher(UUID id, VoucherCommand command, UUID actorId, RequestContext context) {
        requireActor(actorId);
        validateVoucher(command);
        VoucherEntity voucher = voucherRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy voucher."));
        if (voucher.getScope() != VoucherScope.PLATFORM
                || voucher.getFundingSource() != VoucherFundingSource.PLATFORM
                || voucher.getShopId() != null) {
            throw new ResourceNotFoundException("Không tìm thấy voucher.");
        }
        VoucherAuditSnapshot before = voucherSnapshot(voucher);
        String code = normalizeRequired(command.code(), "Mã voucher").toUpperCase(java.util.Locale.ROOT);
        long activeReservationCount = Math.max(voucher.getUsedCount(), voucherUsageRepository.countByVoucherId(id));
        long historicalUseCount = Math.max(activeReservationCount, appliedDiscounts.countByVoucherId(id));
        if (historicalUseCount > 0 && (!code.equalsIgnoreCase(voucher.getCode())
                || command.type() != voucher.getType()
                || command.valueAmount() != voucher.getValueAmount()
                || command.minimumOrderAmount() != voucher.getMinimumOrderAmount()
                || !java.util.Objects.equals(command.maximumDiscountAmount(), voucher.getMaximumDiscountAmount()))) {
            throw new BusinessConflictException("Voucher đã phát sinh sử dụng nên không thể thay đổi điều kiện giảm giá.");
        }
        if (voucherRepository.findByCodeIgnoreCase(code)
                .filter(existing -> !id.equals(existing.getId())).isPresent()) {
            throw new BusinessConflictException("Mã voucher đã tồn tại.");
        }
        if (command.totalQuota() != null && command.totalQuota() < activeReservationCount) {
            throw new BusinessConflictException("Quota tổng không được thấp hơn số lượt đang được giữ.");
        }
        long maxUsedByOneUser = voucherUsageRepository.maxUsageCountByVoucherId(id);
        if (command.perUserQuota() != null && command.perUserQuota() < maxUsedByOneUser) {
            throw new BusinessConflictException("Quota mỗi người dùng không được thấp hơn số lượt đã dùng của một người.");
        }
        applyVoucher(voucher, command, code);
        try {
            voucher = voucherRepository.save(voucher);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessConflictException("Mã voucher đã tồn tại.");
        }
        auditLog.record("VOUCHER_UPDATED", actorId, "Voucher", voucher.getId(),
                voucherChanges(before, voucher), context);
        return voucher;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deactivateVoucher(UUID id, UUID actorId, RequestContext context) {
        requireActor(actorId);
        VoucherEntity voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy voucher."));
        if (voucher.getScope() != VoucherScope.PLATFORM
                || voucher.getFundingSource() != VoucherFundingSource.PLATFORM
                || voucher.getShopId() != null) {
            throw new ResourceNotFoundException("Không tìm thấy voucher.");
        }
        boolean previousActive = voucher.isActive();
        voucher.setActive(false);
        voucherRepository.save(voucher);
        auditLog.record("VOUCHER_DEACTIVATED", actorId, "Voucher", id,
                Map.of("active", previousActive + " -> false"), context);
    }

    private void applyCategory(CategoryEntity category, CategoryCommand command) {
        Long parentId = command.parentId();
        if (parentId != null) {
            if (parentId.equals(category.getId())) {
                throw new BusinessConflictException("Danh mục không thể làm cha của chính nó.");
            }
            CategoryEntity parent = categoryRepository.findById(parentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy danh mục cha."));
            if (!parent.isActive() && !java.util.Objects.equals(category.getParentId(), parentId)) {
                throw new BusinessConflictException("Danh mục cha phải đang hoạt động.");
            }
        }
        category.setParentId(parentId);
        category.setDescription(sanitizer.trimToNull(command.description(), 1000));
        category.setImageUrl(sanitizer.requireHttpUrlOrNull(command.imageUrl(), 2048, "URL hình ảnh"));
        category.setDisplayOrder(command.displayOrder());
        category.setActive(command.active() == null || command.active());
    }

    private void ensureUniqueCategory(CategoryEntity category, Long id) {
        categoryRepository.findByNameIgnoreCase(category.getName())
                .filter(existing -> !java.util.Objects.equals(existing.getId(), id))
                .ifPresent(existing -> { throw new BusinessConflictException("Tên danh mục đã tồn tại."); });
        categoryRepository.findBySlugIgnoreCase(category.getSlug())
                .filter(existing -> !java.util.Objects.equals(existing.getId(), id))
                .ifPresent(existing -> { throw new BusinessConflictException("Slug danh mục đã tồn tại."); });
        if (id != null && category.getParentId() != null) {
            Long cursor = category.getParentId();
            java.util.Set<Long> visited = new java.util.HashSet<>();
            while (cursor != null && visited.add(cursor)) {
                if (id.equals(cursor)) {
                    throw new BusinessConflictException("Cây danh mục không được chứa vòng lặp.");
                }
                cursor = categoryRepository.findById(cursor).map(CategoryEntity::getParentId).orElse(null);
            }
        }
    }

    private void applyVoucher(VoucherEntity voucher, VoucherCommand command, String code) {
        voucher.setCode(code);
        voucher.setType(command.type());
        voucher.setValueAmount(command.valueAmount());
        voucher.setMinimumOrderAmount(command.minimumOrderAmount());
        voucher.setMaximumDiscountAmount(command.maximumDiscountAmount());
        voucher.setStartsAt(command.startsAt());
        voucher.setEndsAt(command.endsAt());
        voucher.setTotalQuota(command.totalQuota());
        voucher.setPerUserQuota(command.perUserQuota());
        voucher.setActive(command.active() == null || command.active());
    }

    private void validateVoucher(VoucherCommand command) {
        if (command.type() == null || command.startsAt() == null || command.endsAt() == null) {
            throw new BusinessConflictException("Voucher thiếu thông tin bắt buộc.");
        }
        if (!command.endsAt().isAfter(command.startsAt())) {
            throw new BusinessConflictException("Thời gian kết thúc voucher phải sau thời gian bắt đầu.");
        }
        if (command.maximumDiscountAmount() != null && command.maximumDiscountAmount() < 0) {
            throw new BusinessConflictException("Mức giảm tối đa không được âm.");
        }
        if (command.minimumOrderAmount() < 0) {
            throw new BusinessConflictException("Đơn tối thiểu không được âm.");
        }
        if (command.totalQuota() != null && command.totalQuota() < 0) {
            throw new BusinessConflictException("Quota tổng không được âm.");
        }
        if (command.perUserQuota() != null && command.perUserQuota() < 0) {
            throw new BusinessConflictException("Quota mỗi người dùng không được âm.");
        }
        if (command.type() == VoucherType.SHIPPING_FREE) {
            if (command.valueAmount() != 0) {
                throw new BusinessConflictException("Voucher miễn phí vận chuyển không cần giá trị giảm; hãy để bằng 0.");
            }
        } else if (command.valueAmount() <= 0) {
            throw new BusinessConflictException("Giá trị voucher phải lớn hơn 0.");
        }
        if (command.type() == VoucherType.PERCENT && command.valueAmount() > 100) {
            throw new BusinessConflictException("Voucher phần trăm không được vượt quá 100%.");
        }
    }

    private String normalizeRequired(String value, String field) {
        String normalized = sanitizer.plainText(value);
        if (normalized.isBlank()) throw new BusinessConflictException(field + " không được để trống.");
        return normalized;
    }

    private CategoryAuditSnapshot categorySnapshot(CategoryEntity category) {
        return new CategoryAuditSnapshot(category.getName(), category.getSlug(), category.getParentId(),
                category.getDisplayOrder(), category.isActive());
    }

    private Map<String, Object> categoryChanges(CategoryAuditSnapshot before, CategoryEntity after) {
        return changes(Map.of("name", before.name(), "slug", before.slug(),
                        "parentId", String.valueOf(before.parentId()), "displayOrder", before.displayOrder(),
                        "active", before.active()),
                Map.of("name", after.getName(), "slug", after.getSlug(),
                        "parentId", String.valueOf(after.getParentId()), "displayOrder", after.getDisplayOrder(),
                        "active", after.isActive()));
    }

    private VoucherAuditSnapshot voucherSnapshot(VoucherEntity voucher) {
        return new VoucherAuditSnapshot(voucher.getCode(), voucher.getType(), voucher.getScope(),
                voucher.getShopId(), voucher.getFundingSource(), voucher.getValueAmount(),
                voucher.getMinimumOrderAmount(), voucher.getMaximumDiscountAmount(), voucher.getStartsAt(),
                voucher.getEndsAt(), voucher.getTotalQuota(), voucher.getPerUserQuota(), voucher.isActive());
    }

    private Map<String, Object> voucherChanges(VoucherAuditSnapshot before, VoucherEntity after) {
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("code", before.code());
        previous.put("type", before.type());
        previous.put("scope", before.scope());
        previous.put("shopId", before.shopId());
        previous.put("fundingSource", before.fundingSource());
        previous.put("valueAmount", before.valueAmount());
        previous.put("minimumOrderAmount", before.minimumOrderAmount());
        previous.put("maximumDiscountAmount", before.maximumDiscountAmount());
        previous.put("startsAt", before.startsAt());
        previous.put("endsAt", before.endsAt());
        previous.put("totalQuota", before.totalQuota());
        previous.put("perUserQuota", before.perUserQuota());
        previous.put("active", before.active());
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("code", after.getCode());
        current.put("type", after.getType());
        current.put("scope", after.getScope());
        current.put("shopId", after.getShopId());
        current.put("fundingSource", after.getFundingSource());
        current.put("valueAmount", after.getValueAmount());
        current.put("minimumOrderAmount", after.getMinimumOrderAmount());
        current.put("maximumDiscountAmount", after.getMaximumDiscountAmount());
        current.put("startsAt", after.getStartsAt());
        current.put("endsAt", after.getEndsAt());
        current.put("totalQuota", after.getTotalQuota());
        current.put("perUserQuota", after.getPerUserQuota());
        current.put("active", after.isActive());
        return changes(previous, current);
    }

    private Map<String, Object> changes(Map<String, ?> before, Map<String, ?> after) {
        Map<String, Object> result = new LinkedHashMap<>();
        before.forEach((field, value) -> {
            Object next = after.get(field);
            if (!Objects.equals(value, next)) {
                result.put(field, String.valueOf(value) + " -> " + next);
            }
        });
        return result;
    }

    private void requireActor(UUID actorId) {
        if (actorId == null) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Admin actor is required for marketplace mutations.");
        }
    }

    private record CategoryAuditSnapshot(String name, String slug, Long parentId, int displayOrder,
                                         boolean active) {
    }

    private record VoucherAuditSnapshot(String code, com.utewedding.app.domain.VoucherType type,
                                        VoucherScope scope, UUID shopId, VoucherFundingSource fundingSource,
                                        long valueAmount,
                                        long minimumOrderAmount, Long maximumDiscountAmount,
                                        Instant startsAt, Instant endsAt, Integer totalQuota,
                                        Integer perUserQuota, boolean active) {
    }
}
