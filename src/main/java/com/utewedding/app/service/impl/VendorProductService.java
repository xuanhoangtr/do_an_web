package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ManagedMediaStatus;
import com.utewedding.app.domain.ProductModerationStatus;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.entity.ProductImageEntity;
import com.utewedding.app.domain.entity.ProductModerationHistoryEntity;
import com.utewedding.app.dto.request.VendorProductForm;
import com.utewedding.app.dto.response.VendorProductView;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.CatalogCategoryRepository;
import com.utewedding.app.repository.ManagedMediaObjectRepository;
import com.utewedding.app.repository.ProductImageRepository;
import com.utewedding.app.repository.ProductModerationHistoryRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.service.port.AuditLogPort;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shop-scoped product content commands. Stock and moderation controls are deliberately separate. */
@Service
public class VendorProductService {
    private static final int PAGE_SIZE = 20;
    private final VendorWorkspaceContext workspaces;
    private final ShopAccessPolicy shopPolicy;
    private final ProductRepository products;
    private final ProductImageRepository images;
    private final ProductModerationHistoryRepository moderationHistory;
    private final ManagedMediaObjectRepository media;
    private final CatalogCategoryRepository categories;
    private final TextSanitizer sanitizer;
    private final AuditLogPort audit;
    private final EntityManager entityManager;

    public VendorProductService(VendorWorkspaceContext workspaces, ShopAccessPolicy shopPolicy,
                                ProductRepository products, ProductImageRepository images,
                                ProductModerationHistoryRepository moderationHistory,
                                ManagedMediaObjectRepository media, CatalogCategoryRepository categories,
                                TextSanitizer sanitizer, AuditLogPort audit, EntityManager entityManager) {
        this.workspaces = workspaces;
        this.shopPolicy = shopPolicy;
        this.products = products;
        this.images = images;
        this.moderationHistory = moderationHistory;
        this.media = media;
        this.categories = categories;
        this.sanitizer = sanitizer;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Page<VendorProductView> list(UserPrincipal actor, int requestedPage) {
        var workspace = workspaces.requireWorkspace(actor);
        int page = Math.max(0, requestedPage);
        Page<ProductEntity> result = products.findByShopIdOrderByUpdatedAtDescIdAsc(
                workspace.shopId(), PageRequest.of(page, PAGE_SIZE, Sort.unsorted()));
        var imageRows = result.getContent().isEmpty() ? java.util.List.<ProductImageEntity>of()
                : images.findByProductIdInOrderByProductIdAscDisplayOrderAscCreatedAtAsc(
                        result.getContent().stream().map(ProductEntity::getId).toList());
        Map<UUID, String> primaryImages = new java.util.HashMap<>();
        Map<UUID, UUID> primaryMediaIds = new java.util.HashMap<>();
        for (ProductImageEntity image : imageRows) {
            if (image.isPrimaryImage()) {
                primaryImages.putIfAbsent(image.getProductId(), image.getImageUrl());
                primaryMediaIds.putIfAbsent(image.getProductId(), image.getManagedMediaId());
            }
        }
        return result.map(product -> view(product,
                primaryImages.getOrDefault(product.getId(), product.getImageUrl()),
                primaryMediaIds.get(product.getId())));
    }

    @Transactional(readOnly = true)
    public VendorProductView get(UserPrincipal actor, UUID productId) {
        var workspace = workspaces.requireWorkspace(actor);
        ProductEntity product = products.findByIdAndShopId(productId, workspace.shopId())
                .orElseThrow(VendorProductService::notFound);
        return view(product, primaryImage(product), primaryMediaId(product));
    }

    @Transactional(readOnly = true)
    public UUID shopId(UserPrincipal actor) {
        return workspaces.requireWorkspace(actor).shopId();
    }

    /** Authorizes a product mutation before a controller performs an external media upload. */
    @Transactional
    public void authorizeMutation(UserPrincipal actor) {
        var workspace = workspaces.requireWorkspace(actor);
        shopPolicy.requireMutation(actor, workspace.shopId());
    }

    @Transactional(rollbackFor = Exception.class)
    public VendorProductView create(UserPrincipal actor, VendorProductForm form) {
        if (form == null) throw new BusinessConflictException("Thông tin sản phẩm không hợp lệ.");
        var workspace = workspaces.requireWorkspace(actor);
        shopPolicy.requireMutation(actor, workspace.shopId());
        validateForm(form);
        ProductEntity product = new ProductEntity();
        product.setShopId(workspace.shopId());
        applyContent(form, product);
        product.setStockQuantity(0);
        product.setSellerVisible(true);
        product.setActive(false);
        product.setModerationStatus(ProductModerationStatus.PENDING);
        product.setModerationReason(null);
        product.setModeratedAt(null);
        product.setModeratedBy(null);
        product = products.saveAndFlush(product);
        syncPrimaryImage(product, form.getManagedMediaId(), actor);
        moderationHistory.save(new ProductModerationHistoryEntity(product.getId(), product.getShopId(), null,
                ProductModerationStatus.PENDING, "Chủ cửa hàng gửi sản phẩm để kiểm duyệt.", actor.getId()));
        audit.record("PRODUCT_CREATED", actor.getId(), "Product", product.getId(),
                Map.of("shopId", product.getShopId(), "moderationStatus", "PENDING"), null);
        return view(product, primaryImage(product), primaryMediaId(product));
    }

    @Transactional(rollbackFor = Exception.class)
    public VendorProductView update(UserPrincipal actor, UUID productId, VendorProductForm form) {
        if (form == null) throw new BusinessConflictException("Thông tin sản phẩm không hợp lệ.");
        var workspace = workspaces.requireWorkspace(actor);
        shopPolicy.requireMutation(actor, workspace.shopId());
        ProductEntity product = products.findByIdAndShopIdForUpdate(productId, workspace.shopId())
                .orElseThrow(VendorProductService::notFound);
        if (form.getVersion() == null || form.getVersion() != product.getVersion()) {
            throw new BusinessConflictException("Thông tin sản phẩm đã thay đổi. Tải lại trang rồi thử lại.");
        }
        validateForm(form);
        ProductSnapshot before = snapshot(product);
        applyContent(form, product);
        syncPrimaryImage(product, form.getManagedMediaId(), actor);
        ProductSnapshot after = snapshot(product);
        if (!before.equals(after)) {
            ProductModerationStatus previous = product.getModerationStatus();
            if (previous != ProductModerationStatus.BLOCKED) {
                product.setModerationStatus(ProductModerationStatus.PENDING);
                product.setModerationReason(null);
                product.setModeratedAt(null);
                product.setModeratedBy(null);
            }
            product.setActive(false);
            products.save(product);
            if (previous != product.getModerationStatus()) {
                moderationHistory.save(new ProductModerationHistoryEntity(product.getId(), product.getShopId(),
                        previous, product.getModerationStatus(), "Nội dung sản phẩm đã thay đổi; cần kiểm duyệt lại.",
                        actor.getId()));
            }
            audit.record("PRODUCT_UPDATED", actor.getId(), "Product", product.getId(),
                    Map.of("shopId", product.getShopId(), "contentChanged", true,
                            "moderationStatus", product.getModerationStatus().name()), null);
        }
        return view(product, primaryImage(product), primaryMediaId(product));
    }

    @Transactional(rollbackFor = Exception.class)
    public VendorProductView toggleVisibility(UserPrincipal actor, UUID productId) {
        var workspace = workspaces.requireWorkspace(actor);
        shopPolicy.requireMutation(actor, workspace.shopId());
        ProductEntity product = products.findByIdAndShopIdForUpdate(productId, workspace.shopId())
                .orElseThrow(VendorProductService::notFound);
        boolean beforeVisible = product.isSellerVisible();
        product.setSellerVisible(!beforeVisible);
        product.setActive(product.isSellerVisible()
                && product.getModerationStatus() == ProductModerationStatus.APPROVED);
        products.save(product);
        audit.record("PRODUCT_STATUS_CHANGED", actor.getId(), "Product", product.getId(),
                Map.of("shopId", product.getShopId(), "sellerVisible", beforeVisible + " -> "
                        + product.isSellerVisible(), "active", product.isActive()), null);
        return view(product, primaryImage(product), primaryMediaId(product));
    }

    @Transactional(rollbackFor = Exception.class)
    public VendorProductView hide(UserPrincipal actor, UUID productId) {
        var workspace = workspaces.requireWorkspace(actor);
        shopPolicy.requireMutation(actor, workspace.shopId());
        ProductEntity product = products.findByIdAndShopIdForUpdate(productId, workspace.shopId())
                .orElseThrow(VendorProductService::notFound);
        product.setSellerVisible(false);
        product.setActive(false);
        products.save(product);
        audit.record("PRODUCT_HIDDEN", actor.getId(), "Product", product.getId(),
                Map.of("shopId", product.getShopId()), null);
        return view(product, primaryImage(product), primaryMediaId(product));
    }

    private void validateForm(VendorProductForm form) {
        String name = sanitizer.trimToNull(form.getName(), 120);
        if (name == null) throw new BusinessConflictException("Tên sản phẩm không được để trống.");
        if (form.getUnitPrice() < 0 || form.getDiscountPercent() < 0 || form.getDiscountPercent() > 100
                || form.getMinOrderQuantity() < 1 || (form.getWeightGrams() != null && form.getWeightGrams() < 0)) {
            throw new BusinessConflictException("Giá, mức giảm, số lượng tối thiểu hoặc khối lượng không hợp lệ.");
        }
        if (form.getCompareAtPrice() != null
                && form.getCompareAtPrice().compareTo(BigDecimal.valueOf(form.getUnitPrice())) <= 0) {
            throw new BusinessConflictException("Giá gốc trước giảm phải lớn hơn giá bán.");
        }
        var category = categories.findById(form.getCategoryId())
                .orElseThrow(() -> new BusinessConflictException("Danh mục đã chọn không tồn tại."));
        if (!category.isActive()) throw new BusinessConflictException("Danh mục đã ngừng hoạt động.");
    }

    private void applyContent(VendorProductForm form, ProductEntity product) {
        String name = sanitizer.trimToNull(form.getName(), 120);
        String requestedSku = sanitizer.trimToNull(form.getSku(), 80);
        if (requestedSku == null) {
            if (product.getId() == null || product.getSku() == null || product.getSku().isBlank()) {
                requestedSku = generateSku(name);
            }
        } else if (!requestedSku.equals(product.getSku())) {
            if (product.getId() == null ? products.existsBySku(requestedSku)
                    : products.existsBySkuAndIdNot(requestedSku, product.getId())) {
                throw new BusinessConflictException("SKU đã được sử dụng cho sản phẩm khác.");
            }
        }
        if (requestedSku != null) product.setSku(requestedSku);
        if (product.getSlug() == null || product.getSlug().isBlank()) {
            product.setSlug(uniqueSlug(name));
        }
        product.setCategoryId(form.getCategoryId());
        product.setName(name);
        product.setShortDescription(sanitizer.trimToNull(form.getShortDescription(), 500));
        product.setDescription(sanitizer.trimToNull(form.getDescription(), 4000));
        product.setCustomizationGuide(sanitizer.trimToNull(form.getCustomizationGuide(), 2000));
        product.setUnitPrice(form.getUnitPrice());
        product.setCompareAtPrice(form.getCompareAtPrice());
        product.setMinOrderQuantity(form.getMinOrderQuantity());
        product.setWeightGrams(form.getWeightGrams());
        product.setDimensions(sanitizer.trimToNull(form.getDimensions(), 160));
        product.setMaterial(sanitizer.trimToNull(form.getMaterial(), 160));
        product.setDiscountPercent(form.getDiscountPercent());
        product.setCustomizable(form.isCustomizable());
    }

    private void syncPrimaryImage(ProductEntity product, UUID mediaId, UserPrincipal actor) {
        ProductImageEntity image = images
                .findFirstByProductIdAndShopIdAndPrimaryImageTrueOrderByDisplayOrderAscCreatedAtAsc(
                        product.getId(), product.getShopId()).orElse(null);
        if (mediaId == null) {
            if (image != null) images.delete(image);
            product.setImageUrl(null);
            return;
        }
        var managed = media.findByIdAndShopIdAndPurposeAndStatusAndDeliveryTypeAndOwnerId(
                        mediaId, product.getShopId(), "PRODUCT", ManagedMediaStatus.READY, "upload", actor.getId())
                .orElseThrow(() -> new BusinessConflictException("Ảnh chưa tải xong hoặc không thuộc cửa hàng này."));
        if (image == null) image = new ProductImageEntity();
        image.setProductId(product.getId());
        image.setShopId(product.getShopId());
        image.setImageUrl(managed.getSecureUrl());
        image.setManagedMediaId(managed.getId());
        image.setPrimaryImage(true);
        image.setDisplayOrder(0);
        image.setMediaType("IMAGE");
        images.save(image);
        product.setImageUrl(managed.getSecureUrl());
    }

    private ProductSnapshot snapshot(ProductEntity p) {
        return new ProductSnapshot(p.getCategoryId(), p.getName(), p.getSku(), p.getShortDescription(),
                p.getDescription(), p.getCustomizationGuide(), p.getUnitPrice(), p.getCompareAtPrice(),
                p.getMinOrderQuantity(), p.getWeightGrams(), p.getDimensions(), p.getMaterial(),
                p.getDiscountPercent(), p.isCustomizable(), p.getImageUrl());
    }

    private String generateSku(String name) {
        String base = ProductEntity.slugify(name);
        if (base.isBlank()) base = "san-pham";
        base = base.substring(0, Math.min(base.length(), 80));
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtext(:sku))")
                .setParameter("sku", base).getSingleResult();
        String candidate = base;
        int suffix = 2;
        while (products.existsBySku(candidate)) {
            String tail = String.format(java.util.Locale.ROOT, "-%02d", suffix++);
            candidate = base.substring(0, Math.min(base.length(), 80 - tail.length())) + tail;
        }
        return candidate;
    }

    private String uniqueSlug(String name) {
        String base = ProductEntity.slugify(name);
        if (base.isBlank()) base = "san-pham";
        String candidate = base;
        int suffix = 2;
        while (products.existsBySlugIgnoreCase(candidate)) candidate = base + "-" + suffix++;
        return candidate;
    }

    private String primaryImage(ProductEntity product) {
        return images.findFirstByProductIdAndShopIdAndPrimaryImageTrueOrderByDisplayOrderAscCreatedAtAsc(
                        product.getId(), product.getShopId())
                .map(ProductImageEntity::getImageUrl).orElse(product.getImageUrl());
    }

    private UUID primaryMediaId(ProductEntity product) {
        return images.findFirstByProductIdAndShopIdAndPrimaryImageTrueOrderByDisplayOrderAscCreatedAtAsc(
                product.getId(), product.getShopId()).map(ProductImageEntity::getManagedMediaId).orElse(null);
    }

    private VendorProductView view(ProductEntity p, String imageUrl, UUID managedMediaId) {
        return new VendorProductView(p.getId(), p.getCategoryId(), p.getName(), p.getSlug(), p.getSku(),
                p.getShortDescription(), p.getDescription(), p.getCustomizationGuide(), p.getUnitPrice(),
                p.getCompareAtPrice(), p.getStockQuantity(), p.getMinOrderQuantity(), p.getWeightGrams(),
                p.getDimensions(), p.getMaterial(), p.getDiscountPercent(), p.isCustomizable(),
                p.isSellerVisible(), p.isActive(), p.getModerationStatus(), p.getModerationReason(),
                imageUrl, managedMediaId,
                p.getUpdatedAt(), p.getVersion());
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Không tìm thấy sản phẩm trong cửa hàng của bạn.");
    }

    private record ProductSnapshot(Long categoryId, String name, String sku, String shortDescription,
                                   String description, String customizationGuide, long unitPrice,
                                   BigDecimal compareAtPrice, int minOrderQuantity, Integer weightGrams,
                                   String dimensions, String material, int discountPercent,
                                   boolean customizable, String imageUrl) { }
}
