package com.utewedding.app.service.impl;

import com.utewedding.app.dto.request.CreateOrderRequest;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/** Canonical SHA-256 fingerprint of a checkout request, independent of key order in JSON. */
public final class CheckoutRequestHasher {
    private static final String SEPARATOR = "\u001f";

    private CheckoutRequestHasher() {
    }

    public static String hash(UUID userId, CreateOrderRequest request) {
        ShippingAddressRequest shipping = request == null ? null : request.shipping();
        String canonical = String.join(SEPARATOR,
                text(userId),
                text(request == null ? null : request.weddingId()),
                code(request == null ? null : request.discountCode()),
                request == null || request.paymentMethod() == null ? "" : request.paymentMethod().name(),
                text(request == null ? null : request.shippingAddressId()),
                text(request == null ? null : request.shippingQuoteId()),
                request == null || request.expectedTotalAmount() == null
                        ? "" : Long.toString(request.expectedTotalAmount()),
                shipping == null ? "" : text(shipping.recipientName()),
                shipping == null ? "" : text(shipping.phone()),
                shipping == null ? "" : text(shipping.addressLine()),
                shipping == null ? "" : text(shipping.provinceCode()),
                shipping == null ? "" : text(shipping.wardCode()));
        return sha256(canonical);
    }

    private static String code(String value) {
        return text(value).toUpperCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
