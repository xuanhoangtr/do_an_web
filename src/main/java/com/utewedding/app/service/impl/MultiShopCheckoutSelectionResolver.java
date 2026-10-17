package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ProductModerationStatus;
import com.utewedding.app.domain.ShopDefaults;
import com.utewedding.app.domain.ShopStatus;
import com.utewedding.app.domain.entity.CartEntity;
import com.utewedding.app.domain.entity.CartItemEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.CartItemRepository;
import com.utewedding.app.repository.CartRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ShopRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.persistence.EntityManager;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/** Resolves row-addressed checkout selection from the authenticated customer's cart. */
@Component
public class MultiShopCheckoutSelectionResolver {
    private static final int MAX_LINE_QUANTITY = 99;

    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final ProductRepository products;
    private final ShopRepository shops;
    private final EntityManager entityManager;

    public MultiShopCheckoutSelectionResolver(CartRepository carts, CartItemRepository cartItems,
                                              ProductRepository products, ShopRepository shops,
                                              EntityManager entityManager) {
        this.carts = carts;
        this.cartItems = cartItems;
        this.products = products;
        this.shops = shops;
        this.entityManager = entityManager;
    }

    /**
     * Resolves all rows and derives each shop from its current product. For placement, this method
     * locks shops in UUID order and then products in UUID order, matching the checkout lock plan.
     */
    public Selection resolve(UUID customerId, List<UUID> selectedCartItemIds, boolean lockForCheckout) {
        return resolveInternal(customerId, selectedCartItemIds, lockForCheckout, false).selection();
    }

    /** Resolves preview rows while retaining shop-scoped buyability/stock errors. */
    public PreviewResolution resolveForPreview(UUID customerId, List<UUID> selectedCartItemIds) {
        return resolveInternal(customerId, selectedCartItemIds, false, true);
    }

    private PreviewResolution resolveInternal(UUID customerId, List<UUID> selectedCartItemIds,
                                              boolean lockForCheckout, boolean tolerateUnavailable) {
        if (customerId == null || selectedCartItemIds == null || selectedCartItemIds.isEmpty()
                || selectedCartItemIds.size() > 100 || selectedCartItemIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessConflictException("Chọn ít nhất một sản phẩm hợp lệ trong giỏ hàng.");
        }
        List<UUID> requestedIds = List.copyOf(selectedCartItemIds);
        Set<UUID> uniqueIds = Set.copyOf(requestedIds);
        if (uniqueIds.size() != requestedIds.size()) {
            throw new BusinessConflictException("Danh sách sản phẩm có dòng giỏ hàng bị lặp.");
        }

        CartEntity cart = (lockForCheckout ? carts.findByUserIdForUpdate(customerId) : carts.findByUserId(customerId))
                .orElseThrow(() -> new BusinessConflictException("Giỏ hàng đã thay đổi. Vui lòng tải lại."));
        List<CartItemEntity> cartRows = cartItems.findByCartIdOrderByIdAsc(cart.getId());
        Map<UUID, CartItemEntity> rowsById = cartRows.stream()
                .collect(Collectors.toMap(CartItemEntity::getId, Function.identity()));
        List<CartItemEntity> selectedRows = requestedIds.stream().map(rowsById::get).toList();
        if (selectedRows.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessConflictException("Một hoặc nhiều sản phẩm đã rời khỏi giỏ hàng. Vui lòng tải lại.");
        }
        if (selectedRows.stream().anyMatch(row -> !customerId.equals(row.getUserId())
                || !cart.getId().equals(row.getCartId()))) {
            throw new BusinessConflictException("Sản phẩm đã chọn không thuộc giỏ hàng của bạn.");
        }

        List<CartItemEntity> orderedRows = selectedRows.stream()
                .sorted(Comparator.comparing(CartItemEntity::getProductId).thenComparing(CartItemEntity::getId))
                .toList();
        Map<UUID, ProductEntity> preliminary = new LinkedHashMap<>();
        for (CartItemEntity row : orderedRows) {
            ProductEntity product = products.findById(row.getProductId()).orElse(null);
            if (product == null) {
                throw new BusinessConflictException("Một sản phẩm đã bị xóa khỏi cửa hàng. Vui lòng bỏ dòng đó khỏi giỏ hàng.");
            }
            preliminary.put(row.getProductId(), product);
        }

        List<UUID> shopIds = preliminary.values().stream().map(MultiShopCheckoutSelectionResolver::shopId)
                .distinct().sorted().toList();
        Map<UUID, String> shopNames = new LinkedHashMap<>();
        Map<UUID, String> errors = new LinkedHashMap<>();
        for (UUID shopId : shopIds) {
            if (ShopDefaults.PLATFORM_SHOP_ID.equals(shopId)) {
                shopNames.put(shopId, ShopDefaults.PLATFORM_SHOP_NAME);
                continue;
            }
            ShopEntity shop = (lockForCheckout ? shops.findByIdForUpdate(shopId) : shops.findById(shopId))
                    .orElse(null);
            if (shop == null) {
                if (!tolerateUnavailable) {
                    throw new BusinessConflictException("Cửa hàng của sản phẩm không còn khả dụng.");
                }
                shopNames.put(shopId, "Cửa hàng không khả dụng");
                errors.put(shopId, "Cửa hàng của sản phẩm không còn khả dụng.");
                continue;
            }
            if (shop.getStatus() != ShopStatus.ACTIVE) {
                String message = "Cửa hàng " + shop.getDisplayName() + " hiện không nhận đơn hàng.";
                if (!tolerateUnavailable) throw new BusinessConflictException(message);
                errors.put(shopId, message);
            }
            shopNames.put(shopId, shop.getDisplayName());
        }

        Map<UUID, ProductEntity> current = new LinkedHashMap<>();
        for (UUID productId : orderedRows.stream().map(CartItemEntity::getProductId).distinct().sorted().toList()) {
            ProductEntity product;
            if (lockForCheckout) {
                ProductEntity previouslyRead = preliminary.get(productId);
                // The preliminary read is intentionally unlocked. Detach it before acquiring the
                // checkout lock so a concurrent stock reservation cannot leave Hibernate holding
                // a stale @Version value in the persistence context while this query waits.
                if (previouslyRead != null && entityManager.contains(previouslyRead)) {
                    entityManager.detach(previouslyRead);
                }
                try {
                    product = products.findByIdForUpdateIncludingInactive(productId).orElse(null);
                } catch (OptimisticLockingFailureException exception) {
                    throw new BusinessConflictException(
                            "Tồn kho sản phẩm vừa thay đổi. Vui lòng tải lại giỏ hàng và thử lại.");
                }
            } else {
                product = preliminary.get(productId);
            }
            if (product == null) {
                throw new BusinessConflictException("Sản phẩm trong giỏ không còn kinh doanh.");
            }
            current.put(productId, product);
        }

        Map<UUID, List<SelectedRow>> grouped = new LinkedHashMap<>();
        for (CartItemEntity row : orderedRows) {
            ProductEntity product = current.get(row.getProductId());
            UUID ownerShop = shopId(product);
            if (!shopIds.contains(ownerShop)) {
                throw new BusinessConflictException("Cửa hàng của sản phẩm đã thay đổi. Vui lòng tải lại giỏ hàng.");
            }
            String buyabilityError = buyabilityError(row, product);
            if (buyabilityError != null) {
                if (!tolerateUnavailable) throw new BusinessConflictException(buyabilityError);
                errors.merge(ownerShop, buyabilityError, MultiShopCheckoutSelectionResolver::joinErrors);
            }
            grouped.computeIfAbsent(ownerShop, ignored -> new ArrayList<>())
                    .add(new SelectedRow(row, product, ownerShop));
        }
        Map<UUID, Long> requestedQuantityByProduct = new LinkedHashMap<>();
        orderedRows.forEach(row -> requestedQuantityByProduct.merge(row.getProductId(), (long) row.getQuantity(),
                Math::addExact));
        requestedQuantityByProduct.forEach((productId, quantity) -> {
            if (quantity > current.get(productId).getStockQuantity()) {
                String message = "Tồn kho của sản phẩm " + current.get(productId).getName()
                        + " không đủ cho các dòng đã chọn.";
                UUID ownerShop = shopId(current.get(productId));
                if (!tolerateUnavailable) throw new BusinessConflictException(message);
                errors.merge(ownerShop, message, MultiShopCheckoutSelectionResolver::joinErrors);
            }
        });

        List<ShopSelection> selections = grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    List<SelectedRow> shopRows = List.copyOf(entry.getValue());
                    String fingerprint = fingerprint(entry.getKey(), shopRows);
                    return new ShopSelection(entry.getKey(), shopNames.get(entry.getKey()), shopRows, fingerprint);
                }).toList();
        String revision = selectionRevision(selections);
        return new PreviewResolution(new Selection(customerId, cart.getId(), selections, revision), errors);
    }

    private String buyabilityError(CartItemEntity row, ProductEntity product) {
        if (!product.isActive() || !product.isSellerVisible()
                || product.getModerationStatus() != ProductModerationStatus.APPROVED) {
            return "Sản phẩm " + product.getName() + " hiện chưa thể đặt hàng.";
        }
        int quantity = row.getQuantity();
        if (quantity < 1 || quantity > MAX_LINE_QUANTITY || quantity > product.getStockQuantity()) {
            return "Tồn kho của sản phẩm " + product.getName()
                    + " đã thay đổi. Vui lòng cập nhật giỏ hàng.";
        }
        if (quantity < product.getMinOrderQuantity()) {
            return "Số lượng tối thiểu của sản phẩm " + product.getName()
                    + " là " + product.getMinOrderQuantity() + ".";
        }
        return null;
    }

    private static String joinErrors(String prior, String next) {
        if (prior == null || prior.isBlank()) return next;
        if (next == null || next.isBlank() || prior.contains(next)) return prior;
        return prior + "; " + next;
    }

    private static UUID shopId(ProductEntity product) {
        return product.getShopId() == null ? ShopDefaults.PLATFORM_SHOP_ID : product.getShopId();
    }

    private String fingerprint(UUID shopId, List<SelectedRow> rows) {
        StringBuilder canonical = new StringBuilder("multi-shop-selection-v1;");
        append(canonical, shopId.toString());
        for (SelectedRow selected : rows) {
            CartItemEntity row = selected.cartItem();
            ProductEntity product = selected.product();
            append(canonical, row.getId().toString());
            append(canonical, row.getProductId().toString());
            append(canonical, Integer.toString(row.getQuantity()));
            append(canonical, row.getCustomizationText());
            append(canonical, Long.toString(product.getUnitPrice()));
            append(canonical, Integer.toString(product.getDiscountPercent()));
            append(canonical, Long.toString(product.getVersion()));
            append(canonical, Integer.toString(product.getStockQuantity()));
        }
        return sha256(canonical.toString());
    }

    private String selectionRevision(List<ShopSelection> groups) {
        StringBuilder canonical = new StringBuilder("multi-shop-revision-v1;");
        for (ShopSelection group : groups) {
            append(canonical, group.shopId().toString());
            append(canonical, group.fingerprint());
        }
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(':').append(safe).append(';');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    public record Selection(UUID customerId, UUID cartId, List<ShopSelection> shops, String revision) {
        public Selection { shops = List.copyOf(shops); }
        public List<UUID> rowIds() {
            return shops.stream().flatMap(shop -> shop.rows().stream())
                    .map(row -> row.cartItem().getId()).toList();
        }
    }

    public record PreviewResolution(Selection selection, Map<UUID, String> errors) {
        public PreviewResolution {
            errors = Map.copyOf(errors);
        }
    }

    public record ShopSelection(UUID shopId, String shopName, List<SelectedRow> rows, String fingerprint) {
        public ShopSelection { rows = List.copyOf(rows); }
        public List<UUID> rowIds() { return rows.stream().map(row -> row.cartItem().getId()).toList(); }
    }

    public record SelectedRow(CartItemEntity cartItem, ProductEntity product, UUID shopId) {
    }
}
