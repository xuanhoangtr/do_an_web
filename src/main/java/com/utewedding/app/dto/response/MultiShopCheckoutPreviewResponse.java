package com.utewedding.app.dto.response;

import com.utewedding.app.domain.PaymentMethod;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Authoritative per-shop pricing result. Invalid quotes are represented as not ready, never as zero. */
public record MultiShopCheckoutPreviewResponse(
        String previewRevision,
        Instant expiresAt,
        ShippingAddressResponse shipping,
        PaymentMethod paymentMethod,
        List<ShopPreview> shops,
        GroupTotals totals,
        boolean ready,
        List<String> errors) {

    public MultiShopCheckoutPreviewResponse {
        shops = shops == null ? List.of() : List.copyOf(shops);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public record ShopPreview(
            UUID shopId,
            String shopName,
            List<UUID> selectedCartItemIds,
            String selectionFingerprint,
            List<ItemLine> lines,
            String shippingProviderCode,
            UUID shippingQuoteId,
            Instant shippingQuoteExpiresAt,
            Long subtotal,
            Long productDiscountAmount,
            Long voucherDiscountAmount,
            Long shippingFeeBeforeDiscount,
            Long shippingDiscountAmount,
            Long shippingFee,
            Long totalAmount,
            boolean buyable,
            String error) {
        public ShopPreview {
            selectedCartItemIds = selectedCartItemIds == null ? List.of() : List.copyOf(selectedCartItemIds);
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record ItemLine(
            UUID cartItemId,
            UUID productId,
            String name,
            String sku,
            String imageUrl,
            String customizationText,
            long unitPrice,
            int quantity,
            int discountPercent,
            long discountAmount,
            long lineTotal) {
    }

    public record GroupTotals(
            Long subtotal,
            Long productDiscountAmount,
            Long voucherDiscountAmount,
            Long shippingFeeBeforeDiscount,
            Long shippingDiscountAmount,
            Long shippingFee,
            Long totalAmount) {
    }
}
