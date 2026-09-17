package com.utewedding.app.service.impl;

import com.utewedding.app.dto.response.CartItemResponse;
import com.utewedding.app.dto.response.CartResponse;
import com.utewedding.app.dto.response.CartShopGroupResponse;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.service.web.WebViewModels;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Resolves a checkout selection from the authenticated user's current cart snapshot. */
@Component
public class CheckoutSelectionResolver {

    public Selection resolve(WebViewModels.CartView currentCart, UUID selectedShopId,
                             String suppliedFingerprint) {
        if (currentCart == null || selectedShopId == null) {
            throw new BusinessConflictException("Chọn một cửa hàng trong giỏ hàng để tiếp tục.");
        }
        WebViewModels.ShopGroupView group = currentCart.getShopGroups().stream()
                .filter(candidate -> selectedShopId.equals(candidate.getShopId()))
                .findFirst()
                .orElseThrow(() -> new BusinessConflictException(
                        "Nhóm sản phẩm đã thay đổi hoặc không còn trong giỏ hàng. Vui lòng tải lại giỏ hàng."));
        if (!group.isCheckoutAvailable()) {
            throw new BusinessConflictException(group.getUnavailableReason() == null
                    ? "Một số sản phẩm trong nhóm này hiện chưa thể thanh toán."
                    : group.getUnavailableReason());
        }
        List<CartItemResponse> items = group.getItems().stream().map(CheckoutSelectionResolver::toResponse).toList();
        return resolve(group.getShopId(), group.getShopName(), items, group.getSubtotal(),
                group.getDiscountAmount(), group.getTotalAmount(), suppliedFingerprint);
    }

    public Selection resolve(CartResponse currentCart, UUID selectedShopId, String suppliedFingerprint) {
        if (currentCart == null || selectedShopId == null) {
            throw new BusinessConflictException("Chọn một cửa hàng trong giỏ hàng để tiếp tục.");
        }
        CartShopGroupResponse group = currentCart.shopGroups().stream()
                .filter(candidate -> selectedShopId.equals(candidate.shopId()))
                .findFirst()
                .orElseThrow(() -> new BusinessConflictException(
                        "Nhóm sản phẩm đã thay đổi hoặc không còn trong giỏ hàng. Vui lòng tải lại giỏ hàng."));
        if (!group.checkoutAvailable()) {
            throw new BusinessConflictException(group.unavailableReason() == null
                    ? "Một số sản phẩm trong nhóm này hiện chưa thể thanh toán."
                    : group.unavailableReason());
        }
        return resolve(selectedShopId, group.shopName(), group.items(), group.subtotal(),
                group.discountAmount(), group.totalAmount(), suppliedFingerprint);
    }

    private Selection resolve(UUID selectedShopId, String shopName, List<CartItemResponse> items,
                              long subtotal, long discountAmount, long totalAmount,
                              String suppliedFingerprint) {
        String currentFingerprint = fingerprint(selectedShopId, items);
        if (!constantTimeEquals(currentFingerprint, suppliedFingerprint)) {
            throw new BusinessConflictException("Giỏ hàng đã thay đổi. Vui lòng xác nhận lại nhóm sản phẩm.");
        }
        return new Selection(selectedShopId, shopName, items, subtotal,
                discountAmount, totalAmount, currentFingerprint);
    }

    public static String fingerprint(UUID shopId, List<CartItemResponse> items) {
        if (shopId == null || items == null || items.isEmpty()) return "";
        List<CartItemResponse> ordered = new ArrayList<>(items);
        ordered.sort(Comparator
                .comparing(CartItemResponse::productId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> value(item.customizationText()))
                .thenComparingInt(CartItemResponse::quantity)
                .thenComparingLong(CartItemResponse::unitPrice)
                .thenComparingInt(CartItemResponse::discountPercent));
        StringBuilder canonical = new StringBuilder();
        append(canonical, shopId.toString());
        for (CartItemResponse item : ordered) {
            append(canonical, item.productId() == null ? "" : item.productId().toString());
            append(canonical, Integer.toString(item.quantity()));
            append(canonical, value(item.customizationText()));
            append(canonical, Long.toString(item.unitPrice()));
            append(canonical, Integer.toString(item.discountPercent()));
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static CartItemResponse toResponse(WebViewModels.CartItemView item) {
        return new CartItemResponse(item.getId(), item.getProductId(), item.getName(), item.getSku(),
                item.getImageUrl(), item.getUnitPrice(), item.getQuantity(), item.getDiscountPercent(),
                item.getDiscountAmount(), item.getLineTotal(), item.getCustomizationText(), item.getShopId(),
                item.getShopName(), item.isCheckoutAvailable(), item.getUnavailableReason());
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    public record Selection(UUID shopId, String shopName, List<CartItemResponse> items,
                            long subtotal, long discountAmount, long totalAmount,
                            String fingerprint) {
        public Selection {
            items = List.copyOf(items);
        }
    }
}
