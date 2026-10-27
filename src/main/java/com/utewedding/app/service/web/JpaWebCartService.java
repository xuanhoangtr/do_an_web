package com.utewedding.app.service.web;

import com.utewedding.app.dto.request.AddCartItemRequest;
import com.utewedding.app.dto.response.CartResponse;
import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.service.CartService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class JpaWebCartService implements WebCartService {

    private final CartService cartService;
    private final MarketplaceFeatureFlags featureFlags;

    public JpaWebCartService(CartService cartService) {
        this(cartService, new MarketplaceFeatureFlags());
    }

    @Autowired
    public JpaWebCartService(CartService cartService, MarketplaceFeatureFlags featureFlags) {
        this.cartService = cartService;
        this.featureFlags = featureFlags == null ? new MarketplaceFeatureFlags() : featureFlags;
    }

    @Override
    public WebViewModels.CartView get(UUID userId) {
        return toView(cartService.getCart(userId));
    }

    @Override
    public WebViewModels.CartView add(UUID userId, UUID productId, int quantity, String customizationText) {
        return toView(cartService.addItem(userId,
                new AddCartItemRequest(productId, quantity, customizationText)));
    }

    @Override
    public WebViewModels.CartView update(UUID userId, UUID cartItemId, String action) {
        if (action == null || !java.util.Set.of("increase", "decrease", "remove")
                .contains(action.toLowerCase(java.util.Locale.ROOT))) {
            throw new com.utewedding.app.exception.BusinessConflictException("Thao tác giỏ hàng không hợp lệ. Vui lòng tải lại giỏ hàng và thử lại.");
        }
        return toView(cartService.changeItemQuantity(userId, cartItemId, action));
    }

    @Override
    public WebViewModels.CartView remove(UUID userId, UUID cartItemId) {
        return toView(cartService.removeItemById(userId, cartItemId));
    }

    private WebViewModels.CartView toView(CartResponse response) {
        List<WebViewModels.CartItemView> items = response.items().stream()
                .map(item -> new WebViewModels.CartItemView(item.id(), item.productId(), item.sku(),
                        item.productName(), item.unitPrice(), item.quantity(), item.discountPercent(),
                        item.discountAmount(), item.lineTotal(), item.imageUrl(), item.shopId(),
                        item.shopName(), item.customizationText(), item.checkoutAvailable(),
                        item.unavailableReason()))
                .toList();
        Map<UUID, WebViewModels.CartItemView> itemViewsById = items.stream()
                .collect(Collectors.toMap(WebViewModels.CartItemView::getId, Function.identity()));
        List<WebViewModels.ShopGroupView> groups = response.shopGroups().stream()
                .map(group -> new WebViewModels.ShopGroupView(group.shopId(), group.shopName(),
                        group.items().stream().map(item -> itemViewsById.get(item.id())).toList(),
                        group.subtotal(), group.discountAmount(), group.totalAmount(),
                        group.checkoutAvailable(), group.unavailableReason(), group.selectionFingerprint()))
                .toList();
        return new WebViewModels.CartView(response.id(), items, groups, response.subtotal(), 0,
                response.discountAmount(), 0, response.totalAmount(),
                featureFlags.isEnabled(Feature.MULTI_SHOP_CART));
    }
}
