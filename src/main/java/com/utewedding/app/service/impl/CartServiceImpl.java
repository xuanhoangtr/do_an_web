package com.utewedding.app.service.impl;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.ProductModerationStatus;
import com.utewedding.app.domain.ShopDefaults;
import com.utewedding.app.domain.ShopStatus;
import com.utewedding.app.domain.entity.CartEntity;
import com.utewedding.app.domain.entity.CartItemEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.dto.request.AddCartItemRequest;
import com.utewedding.app.dto.request.UpdateCartItemRequest;
import com.utewedding.app.dto.response.CartItemResponse;
import com.utewedding.app.dto.response.CartResponse;
import com.utewedding.app.dto.response.CartShopGroupResponse;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.CartItemRepository;
import com.utewedding.app.repository.CartRepository;
import com.utewedding.app.repository.ProductImageRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ShopRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.CartService;
import com.utewedding.app.util.ProductOptions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartServiceImpl implements CartService {

    private static final String UNKNOWN_SHOP = "Cửa hàng chưa xác định";

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final MarketplaceFeatureFlags featureFlags;

    public CartServiceImpl(CartRepository cartRepository,
                           CartItemRepository cartItemRepository,
                           ProductRepository productRepository) {
        this(cartRepository, cartItemRepository, productRepository, null, null, null,
                new MarketplaceFeatureFlags());
    }

    public CartServiceImpl(CartRepository cartRepository,
                           CartItemRepository cartItemRepository,
                           ProductRepository productRepository,
                           ProductImageRepository productImageRepository) {
        this(cartRepository, cartItemRepository, productRepository, productImageRepository,
                null, null, new MarketplaceFeatureFlags());
    }

    @Autowired
    public CartServiceImpl(CartRepository cartRepository,
                           CartItemRepository cartItemRepository,
                           ProductRepository productRepository,
                           ProductImageRepository productImageRepository,
                           UserRepository userRepository,
                           ShopRepository shopRepository,
                           MarketplaceFeatureFlags featureFlags) {
        this.cartRepository = Objects.requireNonNull(cartRepository, "cartRepository");
        this.cartItemRepository = Objects.requireNonNull(cartItemRepository, "cartItemRepository");
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository");
        this.productImageRepository = productImageRepository;
        this.userRepository = userRepository;
        this.shopRepository = shopRepository;
        this.featureFlags = featureFlags == null ? new MarketplaceFeatureFlags() : featureFlags;
    }

    @Override
    @Transactional(readOnly = true)
    public CartResponse getCart(UUID userId) {
        if (userId == null) return emptyCart();
        return cartRepository.findByUserId(userId)
                .map(this::toResponse)
                .orElseGet(CartServiceImpl::emptyCart);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CartResponse addItem(UUID userId, AddCartItemRequest request) {
        if (request == null || request.productId() == null) {
            throw new BusinessConflictException("Không xác định được sản phẩm cần thêm.");
        }
        ProductEntity product = activeProduct(request.productId());
        requireShopCartEnabled(product);
        if (!isProductCheckoutAvailable(product)) {
            throw new BusinessConflictException("Sản phẩm hiện không khả dụng để thanh toán.");
        }
        String customizationText = ProductOptions.resolvePhotoSize(
                product.getCustomizationGuide(), request.customizationText());
        CartEntity cart = lockOrCreateCart(userId);
        CartItemEntity item = cartItemRepository.findByCartIdAndProductId(cart.getId(), product.getId())
                .orElseGet(() -> {
                    CartItemEntity created = new CartItemEntity();
                    created.setCartId(cart.getId());
                    created.setUserId(userId);
                    created.setProductId(product.getId());
                    created.setUnitPrice(java.math.BigDecimal.valueOf(product.getUnitPrice()));
                    created.setQuantity(0);
                    return created;
                });
        if (customizationText != null && item.getQuantity() > 0
                && item.getCustomizationText() != null
                && !item.getCustomizationText().equals(customizationText)) {
            throw new BusinessConflictException("Giỏ hàng đã có sản phẩm với "
                    + item.getCustomizationText() + ". Hãy xóa sản phẩm đó trước khi chọn kích thước khác.");
        }
        int newQuantity = Math.addExact(item.getQuantity(), request.quantity());
        assertAvailable(product, newQuantity);
        if (newQuantity < product.getMinOrderQuantity()) {
            throw new BusinessConflictException("Số lượng tối thiểu để đặt sản phẩm này là "
                    + product.getMinOrderQuantity() + ".");
        }
        if (customizationText != null) item.setCustomizationText(customizationText);
        item.setQuantity(newQuantity);
        cartItemRepository.save(item);
        return toResponse(cart);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CartResponse updateQuantity(UUID userId, UpdateCartItemRequest request) {
        if (request == null || request.productId() == null) {
            throw new BusinessConflictException("Không xác định được sản phẩm cần cập nhật.");
        }
        CartEntity cart = lockOrCreateCart(userId);
        CartItemEntity item = cartItemRepository.findByCartIdAndProductId(cart.getId(), request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không có trong giỏ hàng."));
        applyQuantity(cart, item, request.quantity());
        return toResponse(cart);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CartResponse changeItemQuantity(UUID userId, UUID cartItemId, String action) {
        if (cartItemId == null || action == null) {
            throw new BusinessConflictException("Thao tác giỏ hàng không hợp lệ. Vui lòng tải lại giỏ hàng và thử lại.");
        }
        String normalized = action.trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("increase", "decrease", "remove").contains(normalized)) {
            throw new BusinessConflictException("Thao tác giỏ hàng không hợp lệ. Vui lòng tải lại giỏ hàng và thử lại.");
        }
        CartEntity cart = lockOrCreateCart(userId);
        CartItemEntity item = cartItemRepository.findByIdAndCartId(cartItemId, cart.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không có trong giỏ hàng."));
        int quantity = switch (normalized) {
            case "increase" -> Math.addExact(item.getQuantity(), 1);
            case "decrease" -> Math.max(0, item.getQuantity() - 1);
            default -> 0;
        };
        applyQuantity(cart, item, quantity);
        return toResponse(cart);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CartResponse removeItem(UUID userId, UUID productId) {
        if (productId == null) throw new BusinessConflictException("Không xác định được sản phẩm cần xóa.");
        CartEntity cart = lockOrCreateCart(userId);
        CartItemEntity item = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không có trong giỏ hàng."));
        cartItemRepository.delete(item);
        return toResponse(cart);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CartResponse removeItemById(UUID userId, UUID cartItemId) {
        if (cartItemId == null) throw new BusinessConflictException("Không xác định được sản phẩm cần xóa.");
        CartEntity cart = lockOrCreateCart(userId);
        CartItemEntity item = cartItemRepository.findByIdAndCartId(cartItemId, cart.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không có trong giỏ hàng."));
        cartItemRepository.delete(item);
        return toResponse(cart);
    }

    /**
     * Every mutation first locks the user row, then the cart row. The user lock
     * serializes first-cart creation; the cart lock serializes all line changes.
     * This order is shared by add, absolute update, increase/decrease and remove.
     */
    private CartEntity lockOrCreateCart(UUID userId) {
        if (userId == null) throw new ResourceNotFoundException("Không tìm thấy tài khoản.");
        if (userRepository != null) {
            UserEntity user = userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        }
        CartEntity cart = cartRepository.findByUserId(userId).orElse(null);
        if (cart == null) {
            CartEntity created = new CartEntity();
            created.setUserId(userId);
            cartRepository.saveAndFlush(created);
        }
        return cartRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy giỏ hàng."));
    }

    private ProductEntity activeProduct(UUID productId) {
        return productRepository.findByIdAndActiveTrue(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));
    }

    private void requireShopCartEnabled(ProductEntity product) {
        UUID shopId = product.getShopId();
        if (shopId == null) {
            throw new BusinessConflictException("Chưa xác định được cửa hàng của sản phẩm.");
        }
        if (!ShopDefaults.PLATFORM_SHOP_ID.equals(shopId)
                && !featureFlags.isEnabled(Feature.MULTI_SHOP_CART)) {
            throw new BusinessConflictException("Chức năng mua hàng từ nhiều cửa hàng đang được cập nhật.");
        }
        if (shopRepository != null) {
            ShopEntity shop = shopRepository.findById(shopId)
                    .orElseThrow(() -> new BusinessConflictException("Cửa hàng của sản phẩm hiện không khả dụng."));
            if (shop.getStatus() != ShopStatus.ACTIVE) {
                throw new BusinessConflictException("Cửa hàng của sản phẩm hiện không hoạt động.");
            }
        } else if (!ShopDefaults.PLATFORM_SHOP_ID.equals(shopId)) {
            throw new BusinessConflictException("Cửa hàng của sản phẩm hiện không khả dụng.");
        }
    }

    private void assertAvailable(ProductEntity product, int quantity) {
        if (quantity < 1 || quantity > 99) {
            throw new BusinessConflictException("Số lượng phải nằm trong khoảng từ 1 đến 99.");
        }
        if (quantity > product.getStockQuantity()) {
            throw new BusinessConflictException("Sản phẩm không đủ tồn kho.");
        }
    }

    private void applyQuantity(CartEntity cart, CartItemEntity item, int quantity) {
        if (quantity == 0) {
            cartItemRepository.delete(item);
            return;
        }
        if (quantity < 1 || quantity > 99) {
            throw new BusinessConflictException("Số lượng phải nằm trong khoảng từ 1 đến 99.");
        }
        ProductEntity product = productRepository.findById(item.getProductId()).orElse(null);
        if (product != null && quantity < product.getMinOrderQuantity()) {
            throw new BusinessConflictException("Số lượng tối thiểu để đặt sản phẩm này là "
                    + product.getMinOrderQuantity() + ".");
        }
        if (quantity > item.getQuantity()) {
            if (product == null || !isProductCheckoutAvailable(product)) {
                throw new ResourceNotFoundException("Sản phẩm hiện không còn khả dụng.");
            }
            requireShopCartEnabled(product);
            assertAvailable(product, quantity);
        }
        item.setQuantity(quantity);
        if (product != null) item.setUnitPrice(java.math.BigDecimal.valueOf(product.getUnitPrice()));
        cartItemRepository.save(item);
    }

    private CartResponse toResponse(CartEntity cart) {
        long subtotal = 0;
        long discount = 0;
        List<CartItemResponse> items = new ArrayList<>();
        List<CartItemEntity> cartItems = cartItemRepository.findByCartIdOrderByIdAsc(cart.getId());
        Map<UUID, ProductEntity> products = cartItems.stream()
                .map(CartItemEntity::getProductId)
                .distinct()
                .collect(Collectors.collectingAndThen(Collectors.toList(), ids ->
                        productRepository.findAllById(ids).stream().collect(Collectors.toMap(
                                ProductEntity::getId, Function.identity()))));
        Map<UUID, ShopEntity> shops = loadShops(products.values());
        Map<UUID, String> images = new HashMap<>();
        if (productImageRepository != null && !products.isEmpty()) {
            productImageRepository.findByProductIdInOrderByProductIdAscDisplayOrderAscCreatedAtAsc(products.keySet())
                    .forEach(image -> images.putIfAbsent(image.getProductId(), image.getImageUrl()));
        }
        for (CartItemEntity item : cartItems) {
            ProductEntity product = products.get(item.getProductId());
            if (product == null) {
                items.add(new CartItemResponse(item.getId(), item.getProductId(),
                        "Sản phẩm không còn tồn tại", null, null, 0, item.getQuantity(), 0, 0, 0,
                        item.getCustomizationText(), null, UNKNOWN_SHOP, false,
                        "Một sản phẩm trong giỏ hàng không còn tồn tại."));
                continue;
            }
            ShopEntity shop = product.getShopId() == null ? null : shops.get(product.getShopId());
            String shopName = shop == null ? UNKNOWN_SHOP : shop.getDisplayName();
            String unavailableReason = unavailableReason(product, shop, item.getQuantity());
            boolean checkoutAvailable = unavailableReason == null;
            long gross = Math.multiplyExact(product.getUnitPrice(), item.getQuantity());
            long lineDiscount = Math.multiplyExact(gross, product.getDiscountPercent()) / 100;
            subtotal = Math.addExact(subtotal, gross);
            discount = Math.addExact(discount, lineDiscount);
            items.add(new CartItemResponse(item.getId(), product.getId(), product.getName(),
                    product.getSku(), images.getOrDefault(product.getId(), product.getImageUrl()),
                    product.getUnitPrice(), item.getQuantity(), product.getDiscountPercent(),
                    lineDiscount, gross - lineDiscount, item.getCustomizationText(),
                    product.getShopId(), shopName, checkoutAvailable, unavailableReason));
        }
        List<CartShopGroupResponse> groups = buildGroups(items);
        return new CartResponse(cart.getId(), List.copyOf(items), subtotal, discount,
                subtotal - discount, groups);
    }

    private Map<UUID, ShopEntity> loadShops(java.util.Collection<ProductEntity> products) {
        if (shopRepository == null || products.isEmpty()) return Map.of();
        List<UUID> ids = products.stream().map(ProductEntity::getShopId).filter(Objects::nonNull)
                .distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return shopRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ShopEntity::getId, Function.identity()));
    }

    private String unavailableReason(ProductEntity product, ShopEntity shop, int quantity) {
        if (product.getShopId() == null || shop == null) return "Không xác định được cửa hàng của sản phẩm.";
        if (shop.getStatus() != ShopStatus.ACTIVE) return "Cửa hàng của sản phẩm hiện không hoạt động.";
        if (!isProductCheckoutAvailable(product)) return "Sản phẩm hiện không khả dụng để thanh toán.";
        if (product.getStockQuantity() < quantity) return "Số lượng trong giỏ vượt quá số lượng sản phẩm còn hàng.";
        if (!ShopDefaults.PLATFORM_SHOP_ID.equals(product.getShopId())
                && !featureFlags.isEnabled(Feature.MULTI_SHOP_CART)) {
            return "Chức năng mua hàng từ nhiều cửa hàng đang được cập nhật.";
        }
        return null;
    }

    private boolean isProductCheckoutAvailable(ProductEntity product) {
        return product != null && product.isActive() && product.isSellerVisible()
                && product.getModerationStatus() == ProductModerationStatus.APPROVED;
    }

    private List<CartShopGroupResponse> buildGroups(List<CartItemResponse> items) {
        Map<UUID, List<CartItemResponse>> grouped = new LinkedHashMap<>();
        for (CartItemResponse item : items) {
            grouped.computeIfAbsent(item.shopId(), ignored -> new ArrayList<>()).add(item);
        }
        return grouped.entrySet().stream().map(entry -> {
            UUID shopId = entry.getKey();
            List<CartItemResponse> groupItems = List.copyOf(entry.getValue());
            String shopName = groupItems.isEmpty() ? UNKNOWN_SHOP : groupItems.getFirst().shopName();
            long groupSubtotal = 0;
            long groupDiscount = 0;
            String reason = null;
            for (CartItemResponse item : groupItems) {
                groupSubtotal = Math.addExact(groupSubtotal,
                        Math.multiplyExact(item.unitPrice(), item.quantity()));
                groupDiscount = Math.addExact(groupDiscount, item.discountAmount());
                if (!item.checkoutAvailable() && reason == null) reason = item.unavailableReason();
            }
            boolean available = shopId != null && reason == null;
            return new CartShopGroupResponse(shopId, shopName, groupItems, groupSubtotal, groupDiscount,
                    groupSubtotal - groupDiscount, available, reason,
                    CheckoutSelectionResolver.fingerprint(shopId, groupItems));
        }).sorted(Comparator.comparing(CartShopGroupResponse::shopName,
                String.CASE_INSENSITIVE_ORDER).thenComparing(group -> group.shopId() == null
                ? "" : group.shopId().toString())).toList();
    }

    private static CartResponse emptyCart() {
        return new CartResponse(null, List.of(), 0, 0, 0, List.of());
    }
}
