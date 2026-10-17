package com.utewedding.app.service.impl;

import com.utewedding.app.dto.request.MultiShopCheckoutRequest;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Versioned canonical idempotency hash for grouped checkout requests. */
public final class MultiShopCheckoutRequestHasher {
    private MultiShopCheckoutRequestHasher() { }

    public static String hash(UUID customerId, MultiShopCheckoutRequest request) {
        StringBuilder canonical = new StringBuilder("multi-shop-checkout-request-v1;");
        append(canonical, text(customerId));
        request.selectedCartItemIds().stream()
                .sorted(Comparator.nullsFirst(Comparator.naturalOrder()))
                .forEach(id -> append(canonical, text(id)));
        append(canonical, text(request.shippingAddressId()));
        ShippingAddressRequest shipping = request.shipping();
        if (shipping != null) {
            append(canonical, text(shipping.recipientName()));
            append(canonical, text(shipping.phone()));
            append(canonical, text(shipping.addressLine()));
            append(canonical, text(shipping.provinceCode()));
            append(canonical, text(shipping.wardCode()));
        }
        append(canonical, request.paymentMethod() == null ? "" : request.paymentMethod().name());
        request.shops().stream()
                .sorted(Comparator.nullsFirst(Comparator.comparing(MultiShopCheckoutRequest.ShopOptions::shopId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))))
                .forEach(options -> {
                    if (options == null) {
                        append(canonical, "<null-shop-options>");
                        return;
                    }
                    append(canonical, text(options.shopId()));
                    append(canonical, code(options.discountCode()));
                    append(canonical, code(options.shippingDiscountCode()));
                    append(canonical, text(options.customerNote()));
                    append(canonical, text(options.shippingQuoteId()));
                });
        append(canonical, text(request.previewRevision()));
        append(canonical, request.expectedTotalAmount() == null ? "" : request.expectedTotalAmount().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static String code(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }
}
