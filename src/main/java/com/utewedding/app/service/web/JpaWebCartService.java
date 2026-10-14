package com.utewedding.app.service.web;

import com.utewedding.app.dto.request.AddCartItemRequest;
import com.utewedding.app.dto.request.UpdateCartItemRequest;
import com.utewedding.app.dto.response.CartResponse;
import com.utewedding.app.service.CartService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JpaWebCartService implements WebCartService {

    private final CartService cartService;

    public JpaWebCartService(CartService cartService) {
        this.cartService = cartService;
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
        CartResponse current = cartService.getCart(userId);
        var item = current.items().stream()
                .filter(candidate -> candidate.id().equals(cartItemId)
                        || candidate.productId().equals(cartItemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm trong giỏ hàng."));
        int quantity = item.quantity();
        if ("increase".equalsIgnoreCase(action)) quantity++;
        if ("decrease".equalsIgnoreCase(action)) quantity--;
        if ("remove".equalsIgnoreCase(action)) quantity = 0;
        return toView(cartService.updateQuantity(userId,
                new UpdateCartItemRequest(item.productId(), quantity)));
    }

    @Override
    public WebViewModels.CartView remove(UUID userId, UUID cartItemId) {
        CartResponse current = cartService.getCart(userId);
        var item = current.items().stream()
                .filter(candidate -> candidate.id().equals(cartItemId)
                        || candidate.productId().equals(cartItemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm trong giỏ hàng."));
        return toView(cartService.removeItem(userId, item.productId()));
    }

    private WebViewModels.CartView toView(CartResponse response) {
        List<WebViewModels.CartItemView> items = response.items().stream()
                .map(item -> new WebViewModels.CartItemView(item.id(), item.productId(), item.sku(),
                        item.productName(), item.unitPrice(), item.quantity(), item.discountPercent(),
                        item.discountAmount(), item.lineTotal(), item.imageUrl(), "UTEWedding",
                        item.customizationText()))
                .toList();
        return new WebViewModels.CartView(response.id(), items, response.subtotal(), 0,
                response.discountAmount(), 0, response.totalAmount());
    }
}
