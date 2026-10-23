package com.utewedding.app.service.impl;

import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.domain.entity.ProductImageEntity;
import com.utewedding.app.dto.request.AdminProductForm;
import com.utewedding.app.dto.response.AdminProductResponse;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ProductImageRepository;
import com.utewedding.app.repository.CatalogCategoryRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.AdminProductService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.service.port.AuditLogPort;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.utewedding.app.domain.ShopDefaults;
import com.utewedding.app.domain.ProductModerationStatus;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminProductServiceImpl implements AdminProductService {

    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final AuditLogPort auditLog;
    private final TextSanitizer textSanitizer;
    private final UserRepository users;
    @Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired(required = false)
    private com.utewedding.app.repository.ManagedMediaObjectRepository managedMediaObjects;
    @Autowired(required = false)
    private CatalogCategoryRepository categoryRepository;

    @Autowired
    public AdminProductServiceImpl(ProductRepository productRepository,
                                   ProductImageRepository imageRepository,
                                   AuditLogPort auditLog,
                                   TextSanitizer textSanitizer,
                                   UserRepository users) {
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository");
        this.imageRepository = Objects.requireNonNull(imageRepository, "imageRepository");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.textSanitizer = Objects.requireNonNull(textSanitizer, "textSanitizer");
        this.users = Objects.requireNonNull(users, "users");
    }

    @Override
    @Transactional(readOnly = true)
    public AdminProductResponse get(java.util.UUID productId) {
        return toResponse(find(productId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminProductResponse create(UUID actorId, AdminProductForm command, RequestContext context) {
        requireAdminActor(actorId);
        ProductEntity product = new ProductEntity();
        apply(command, product);
        product.setShopId(ShopDefaults.PLATFORM_SHOP_ID);
        product.setModerationStatus(ProductModerationStatus.APPROVED);
        product.setModeratedAt(Instant.now());
        product.setActive(true);
        product = productRepository.save(product);
        syncPrimaryImage(product, command, actorId);
        auditLog.record("PRODUCT_CREATED", actorId, "Product", product.getId(),
                Map.of("active", product.isActive(), "unitPrice", product.getUnitPrice()), context);
        return toResponse(product);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminProductResponse update(UUID actorId, UUID productId, AdminProductForm command,
                                       RequestContext context) {
        requireAdminActor(actorId);
        ProductEntity product = find(productId);
        requirePlatformProduct(product);
        ProductAuditSnapshot before = snapshot(product);
        apply(command, product);
        product = productRepository.save(product);
        syncPrimaryImage(product, command, actorId);
        auditLog.record("PRODUCT_UPDATED", actorId, "Product", product.getId(),
                changes(before, product), context);
        return toResponse(product);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminProductResponse toggle(UUID actorId, UUID productId, RequestContext context) {
        requireAdminActor(actorId);
        ProductEntity product = find(productId);
        requirePlatformProduct(product);
        boolean previousActive = product.isActive();
        boolean previousVisible = product.isSellerVisible();
        product.setSellerVisible(!previousVisible);
        product.setActive(product.isSellerVisible()
                && product.getModerationStatus() == ProductModerationStatus.APPROVED);
        product = productRepository.save(product);
        auditLog.record("PRODUCT_STATUS_CHANGED", actorId, "Product", product.getId(),
                Map.of("active", previousActive + " -> " + product.isActive()), context);
        return toResponse(product);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminProductResponse hide(UUID actorId, UUID productId, RequestContext context) {
        requireAdminActor(actorId);
        ProductEntity product = find(productId);
        requirePlatformProduct(product);
        boolean previousActive = product.isActive();
        product.setSellerVisible(false);
        product.setActive(false);
        product = productRepository.save(product);
        auditLog.record("PRODUCT_HIDDEN", actorId, "Product", product.getId(),
                Map.of("active", previousActive + " -> false"), context);
        return toResponse(product);
    }

    private ProductEntity find(java.util.UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));
    }

    private void requireAdminActor(UUID actorId) {
        var actor = actorId == null ? java.util.Optional.<com.utewedding.app.domain.entity.UserEntity>empty()
                : users.findById(actorId);
        if (actor.isEmpty() || actor.get().getStatus() != UserStatus.ACTIVE
                || (actor.get().getRole() != UserRole.ADMIN && actor.get().getRole() != UserRole.SUPER_ADMIN)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Chỉ quản trị viên hoặc super admin mới được sửa sản phẩm.");
        }
    }

    private void requirePlatformProduct(ProductEntity product) {
        if (!ShopDefaults.PLATFORM_SHOP_ID.equals(product.getShopId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Sản phẩm của cửa hàng được quản lý qua luồng kiểm duyệt riêng.");
        }
    }

    private ProductAuditSnapshot snapshot(ProductEntity product) {
        return new ProductAuditSnapshot(product.getCategoryId(), product.getName(), product.getUnitPrice(),
                product.getStockQuantity(), product.getDiscountPercent(), product.isFeatured(),
                product.getDescription(), product.getMaterial(), product.getImageUrl(), product.getSku(),
                product.getShortDescription(), product.getCustomizationGuide(), product.getCompareAtPrice(),
                product.getMinOrderQuantity(), product.getWeightGrams(), product.getDimensions(),
                product.isCustomizable());
    }

    private Map<String, Object> changes(ProductAuditSnapshot before, ProductEntity after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        addChange(changes, "categoryId", before.categoryId(), after.getCategoryId());
        addChange(changes, "name", before.name(), after.getName());
        addChange(changes, "unitPrice", before.unitPrice(), after.getUnitPrice());
        addChange(changes, "stockQuantity", before.stockQuantity(), after.getStockQuantity());
        addChange(changes, "discountPercent", before.discountPercent(), after.getDiscountPercent());
        addChange(changes, "featured", before.featured(), after.isFeatured());
        addChangedMarker(changes, "descriptionChanged", before.description(), after.getDescription());
        addChangedMarker(changes, "materialChanged", before.material(), after.getMaterial());
        addChangedMarker(changes, "imageUrlChanged", before.imageUrl(), after.getImageUrl());
        addChange(changes, "sku", before.sku(), after.getSku());
        addChangedMarker(changes, "shortDescriptionChanged", before.shortDescription(), after.getShortDescription());
        addChangedMarker(changes, "customizationGuideChanged", before.customizationGuide(), after.getCustomizationGuide());
        addChange(changes, "compareAtPrice", before.compareAtPrice(), after.getCompareAtPrice());
        addChange(changes, "minOrderQuantity", before.minOrderQuantity(), after.getMinOrderQuantity());
        addChange(changes, "weightGrams", before.weightGrams(), after.getWeightGrams());
        addChange(changes, "dimensions", before.dimensions(), after.getDimensions());
        addChange(changes, "customizable", before.customizable(), after.isCustomizable());
        return changes;
    }

    private void addChange(Map<String, Object> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.put(field, String.valueOf(before) + " -> " + after);
        }
    }

    private void addChangedMarker(Map<String, Object> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.put(field, "changed");
        }
    }

    private record ProductAuditSnapshot(Long categoryId, String name, long unitPrice, int stockQuantity,
                                        int discountPercent, boolean featured, String description,
                                        String material, String imageUrl, String sku, String shortDescription,
                                        String customizationGuide, java.math.BigDecimal compareAtPrice,
                                        int minOrderQuantity, Integer weightGrams, String dimensions,
                                        boolean customizable) {
    }

    private void apply(AdminProductForm command, ProductEntity product) {
        String name = textSanitizer.trimToNull(command.getName(), 120);
        String requestedSku = textSanitizer.trimToNull(command.getSku(), 80);
        if (product.getId() == null && (command.isSkuAutoGenerated() || requestedSku == null)) {
            product.setSku(generateSku(name));
        } else if (requestedSku != null && !requestedSku.equals(product.getSku())) {
            if (product.getId() == null
                    ? productRepository.existsBySku(requestedSku)
                    : productRepository.existsBySkuAndIdNot(requestedSku, product.getId())) {
                throw new BusinessConflictException("SKU đã được sử dụng cho sản phẩm khác.");
            }
            product.setSku(requestedSku);
        } else if (product.getSku() == null || product.getSku().isBlank()) {
            product.setSku(generateSku(name));
        }
        if (command.getCompareAtPrice() != null
                && command.getCompareAtPrice().compareTo(java.math.BigDecimal.valueOf(command.getUnitPrice())) <= 0) {
            throw new BusinessConflictException("Giá gốc trước giảm phải lớn hơn giá bán.");
        }
        validateCategory(command.getCategoryId(), product);
        product.setCategoryId(command.getCategoryId());
        product.setName(name);
        if (product.getSlug() == null || product.getSlug().isBlank() || product.getSlug().startsWith("product-")) {
            product.setSlug(uniqueSlug(product.getName(), product.getId()));
        }
        product.setShortDescription(textSanitizer.trimToNull(command.getShortDescription(), 500));
        product.setCustomizationGuide(textSanitizer.trimToNull(command.getCustomizationGuide(), 2000));
        product.setUnitPrice(command.getUnitPrice());
        product.setCompareAtPrice(command.getCompareAtPrice());
        product.setStockQuantity(command.getStockQuantity());
        product.setMinOrderQuantity(command.getMinOrderQuantity());
        product.setWeightGrams(command.getWeightGrams());
        product.setDimensions(textSanitizer.trimToNull(command.getDimensions(), 160));
        product.setDiscountPercent(command.getDiscountPercent());
        product.setCustomizable(command.isCustomizable());
        product.setDescription(textSanitizer.trimToNull(command.getDescription(), 4000));
        product.setMaterial(textSanitizer.trimToNull(command.getMaterial(), 160));
        product.setImageUrl(textSanitizer.requireHttpUrlOrNull(command.getImageUrl(), 2048, "URL hình ảnh"));
        product.setFeatured(command.isFeatured());
    }

    private void validateCategory(Long categoryId, ProductEntity product) {
        if (categoryRepository == null || categoryId == null) return;
        var category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessConflictException("Danh mục đã chọn không tồn tại."));
        if (category.isActive()) return;
        if (product.getId() != null && java.util.Objects.equals(product.getCategoryId(), categoryId)) return;
        throw new BusinessConflictException("Chỉ có thể giữ danh mục hiện tại đã ngừng hoạt động.");
    }

    private String generateSku(String productName) {
        String base = ProductEntity.slugify(productName == null ? "" : productName);
        if (base.isBlank()) base = "san-pham";
        base = base.substring(0, Math.min(base.length(), 80));
        if (jdbcTemplate != null) {
            String skuBase = base;
            jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(hashtext(?))")) {
                    statement.setString(1, skuBase);
                    statement.execute();
                }
                return null;
            });
        }
        String candidate = base;
        int suffix = 2;
        while (productRepository.existsBySku(candidate)) {
            String tail = String.format(java.util.Locale.ROOT, "-%02d", suffix++);
            candidate = base.substring(0, Math.min(base.length(), 80 - tail.length())) + tail;
        }
        return candidate;
    }

    private String uniqueSlug(String name, UUID currentId) {
        String base = ProductEntity.slugify(name);
        if (base.isEmpty()) {
            base = "san-pham";
        }
        String candidate = base;
        int suffix = 2;
        while (currentId == null
                ? productRepository.existsBySlugIgnoreCase(candidate)
                : productRepository.existsBySlugIgnoreCaseAndIdNot(candidate, currentId)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private AdminProductResponse toResponse(ProductEntity product) {
        ProductImageEntity primary = imageRepository
                .findFirstByProductIdAndPrimaryImageTrueOrderByDisplayOrderAscCreatedAtAsc(product.getId())
                .orElse(null);
        String imageUrl = product.getImageUrl() == null && primary != null
                ? primary.getImageUrl() : product.getImageUrl();
        return new AdminProductResponse(product.getId(), product.getCategoryId(), product.getName(),
                product.getUnitPrice(), product.getStockQuantity(), product.getDiscountPercent(),
                product.getDescription(), product.getMaterial(), imageUrl,
                primary != null && java.util.Objects.equals(primary.getImageUrl(), imageUrl)
                        ? primary.getManagedMediaId() : null,
                product.isFeatured(), product.isActive(), product.getSku(), product.getShortDescription(),
                product.getCustomizationGuide(), product.getCompareAtPrice(), product.getMinOrderQuantity(),
                product.getWeightGrams(), product.getDimensions(), product.isCustomizable());
    }

    private void syncPrimaryImage(ProductEntity product, AdminProductForm command, UUID actorId) {
        String imageUrl = textSanitizer.requireHttpUrlOrNull(command.getImageUrl(), 2048, "URL hình ảnh");
        ProductImageEntity image = imageRepository
                .findFirstByProductIdAndPrimaryImageTrueOrderByDisplayOrderAscCreatedAtAsc(product.getId())
                .orElse(null);
        if (imageUrl == null) {
            imageRepository.deleteByProductIdAndPrimaryImageTrue(product.getId());
            return;
        }
        if (image == null) image = new ProductImageEntity();
        UUID managedMediaId = command.getManagedMediaId();
        if (managedMediaId == null && image.getId() != null && imageUrl.equals(image.getImageUrl())) {
            managedMediaId = image.getManagedMediaId();
        }
        if (managedMediaId != null) {
            if (managedMediaObjects == null) throw new BusinessConflictException("Không thể xác minh ảnh sản phẩm.");
            var media = managedMediaObjects.findById(managedMediaId)
                    .orElseThrow(() -> new BusinessConflictException("Không tìm thấy ảnh sản phẩm đã tải lên."));
            boolean alreadyAttachedToThisProduct = image.getId() != null
                    && managedMediaId.equals(image.getManagedMediaId())
                    && imageUrl.equals(image.getImageUrl());
            if (!product.getShopId().equals(media.getShopId())
                    || (!actorId.equals(media.getOwnerId()) && !alreadyAttachedToThisProduct)
                    || !"PRODUCT".equals(media.getPurpose())
                    || !"upload".equals(media.getDeliveryType())
                    || media.getStatus() != com.utewedding.app.domain.ManagedMediaStatus.READY
                    || !imageUrl.equals(media.getSecureUrl())) {
                throw new BusinessConflictException("Ảnh sản phẩm không hợp lệ hoặc không thuộc tài khoản hiện tại.");
            }
        }
        image.setProductId(product.getId());
        image.setShopId(product.getShopId());
        image.setImageUrl(imageUrl);
        image.setManagedMediaId(managedMediaId);
        image.setPrimaryImage(true);
        image.setDisplayOrder(0);
        image.setMediaType("IMAGE");
        imageRepository.save(image);
    }

}
