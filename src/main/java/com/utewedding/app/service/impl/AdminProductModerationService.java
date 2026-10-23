package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ProductModerationStatus;
import com.utewedding.app.domain.ShopDefaults;
import com.utewedding.app.domain.ShopKind;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.UserStatus;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.entity.ProductModerationHistoryEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.dto.response.ProductModerationDetailView;
import com.utewedding.app.dto.response.ProductModerationHistoryView;
import com.utewedding.app.dto.response.ProductModerationView;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ProductModerationHistoryRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ShopRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.TextSanitizer;
import com.utewedding.app.service.port.AuditLogPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrative review of products owned by vendor shops. */
@Service
public class AdminProductModerationService {
    private static final Set<ProductModerationStatus> DECISIONS = Set.of(
            ProductModerationStatus.APPROVED, ProductModerationStatus.REJECTED, ProductModerationStatus.BLOCKED);
    private final ProductRepository products;
    private final ProductModerationHistoryRepository history;
    private final ShopRepository shops;
    private final UserRepository users;
    private final TextSanitizer sanitizer;
    private final AuditLogPort audit;

    public AdminProductModerationService(ProductRepository products,
                                         ProductModerationHistoryRepository history,
                                         ShopRepository shops, UserRepository users,
                                         TextSanitizer sanitizer, AuditLogPort audit) {
        this.products = products;
        this.history = history;
        this.shops = shops;
        this.users = users;
        this.sanitizer = sanitizer;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<ProductModerationView> list(UUID actorId, ProductModerationStatus status, int requestedPage) {
        requireAdmin(actorId);
        int page = Math.max(0, requestedPage);
        Page<ProductEntity> found = products.findVendorProductsForModeration(status,
                ShopDefaults.PLATFORM_SHOP_ID, PageRequest.of(page, 30,
                        Sort.by(Sort.Direction.DESC, "updatedAt")));
        List<UUID> shopIds = found.getContent().stream().map(ProductEntity::getShopId).distinct().toList();
        Map<UUID, ShopEntity> shopById = shops.findAllById(shopIds).stream()
                .collect(Collectors.toMap(ShopEntity::getId, Function.identity()));
        return found.map(product -> view(product, shopById.get(product.getShopId())));
    }

    @Transactional(readOnly = true)
    public ProductModerationDetailView detail(UUID actorId, UUID productId) {
        requireAdmin(actorId);
        ProductEntity product = findVendorProduct(productId);
        ShopEntity shop = shops.findById(product.getShopId()).orElseThrow(AdminProductModerationService::notFound);
        List<ProductModerationHistoryView> events = history.findByProductIdOrderByOccurredAtDescIdDesc(productId)
                .stream().map(this::historyView).toList();
        return new ProductModerationDetailView(view(product, shop), events);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductModerationDetailView decide(UUID actorId, UUID productId, ProductModerationStatus decision,
                                              String rawReason, RequestContext context) {
        requireAdmin(actorId);
        if (decision == null || !DECISIONS.contains(decision)) {
            throw new BusinessConflictException("Quyết định kiểm duyệt không hợp lệ.");
        }
        String reason = sanitizer.trimToNull(rawReason, 1000);
        if ((decision == ProductModerationStatus.REJECTED || decision == ProductModerationStatus.BLOCKED)
                && reason == null) {
            throw new BusinessConflictException("Vui lòng ghi lý do từ chối hoặc chặn sản phẩm.");
        }
        ProductEntity product = products.findByIdForModerationUpdate(productId)
                .orElseThrow(AdminProductModerationService::notFound);
        if (ShopDefaults.PLATFORM_SHOP_ID.equals(product.getShopId())) {
            throw new AccessDeniedException("Sản phẩm nền tảng không thuộc hàng chờ kiểm duyệt nhà bán.");
        }
        ShopEntity shop = shops.findById(product.getShopId()).orElseThrow(AdminProductModerationService::notFound);
        if (shop.getKind() != ShopKind.VENDOR) throw AdminProductModerationService.notFound();

        ProductModerationStatus previous = product.getModerationStatus();
        product.setModerationStatus(decision);
        product.setModerationReason(reason);
        product.setModeratedAt(Instant.now());
        product.setModeratedBy(actorId);
        product.setActive(decision == ProductModerationStatus.APPROVED && product.isSellerVisible());
        products.saveAndFlush(product);
        history.save(new ProductModerationHistoryEntity(product.getId(), product.getShopId(), previous,
                decision, reason, actorId));
        audit.record("PRODUCT_MODERATION_CHANGED", actorId, "Product", product.getId(),
                Map.of("shopId", product.getShopId(), "from", previous.name(), "to", decision.name()), context);
        List<ProductModerationHistoryView> events = history.findByProductIdOrderByOccurredAtDescIdDesc(productId)
                .stream().map(this::historyView).toList();
        return new ProductModerationDetailView(view(product, shop), events);
    }

    private ProductEntity findVendorProduct(UUID productId) {
        ProductEntity product = products.findById(productId).orElseThrow(AdminProductModerationService::notFound);
        if (ShopDefaults.PLATFORM_SHOP_ID.equals(product.getShopId())) throw notFound();
        ShopEntity shop = shops.findById(product.getShopId()).orElseThrow(AdminProductModerationService::notFound);
        if (shop.getKind() != ShopKind.VENDOR) throw notFound();
        return product;
    }

    private void requireAdmin(UUID actorId) {
        var user = actorId == null ? java.util.Optional.<com.utewedding.app.domain.entity.UserEntity>empty()
                : users.findById(actorId);
        if (user.isEmpty() || user.get().getStatus() != UserStatus.ACTIVE
                || (user.get().getRole() != UserRole.ADMIN && user.get().getRole() != UserRole.SUPER_ADMIN)) {
            throw new AccessDeniedException("Chỉ quản trị viên hoặc super admin mới được kiểm duyệt sản phẩm.");
        }
    }

    private ProductModerationView view(ProductEntity product, ShopEntity shop) {
        return new ProductModerationView(product.getId(), product.getShopId(),
                shop == null ? "Cửa hàng" : shop.getDisplayName(), product.getName(), product.getSku(),
                product.getDescription(), product.getImageUrl(), product.getUnitPrice(),
                product.getModerationStatus(), product.getModerationReason(), product.getUpdatedAt());
    }

    private ProductModerationHistoryView historyView(ProductModerationHistoryEntity row) {
        return new ProductModerationHistoryView(row.getId(), row.getFromStatus(), row.getToStatus(),
                row.getReason(), row.getActorUserId(), row.getOccurredAt());
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Không tìm thấy sản phẩm cần kiểm duyệt.");
    }
}
