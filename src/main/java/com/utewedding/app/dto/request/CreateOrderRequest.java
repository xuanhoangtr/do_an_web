package com.utewedding.app.dto.request;

import com.utewedding.app.domain.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateOrderRequest(
        UUID weddingId,
        @NotBlank(message = "Khoá idempotency là bắt buộc.")
        @Size(max = 120) String idempotencyKey,
        @Size(max = 80) String discountCode,
        @NotNull PaymentMethod paymentMethod,
        @Valid ShippingAddressRequest shipping,
        UUID shippingAddressId,
        Long expectedTotalAmount,
        UUID shippingQuoteId,
        @Size(max = 80) String shippingDiscountCode,
        UUID selectedShopId,
        @Size(max = 100) String selectionFingerprint) {

    public CreateOrderRequest(UUID weddingId, String idempotencyKey, String discountCode,
                              PaymentMethod paymentMethod, ShippingAddressRequest shipping,
                              UUID shippingAddressId, Long expectedTotalAmount, UUID shippingQuoteId,
                              String shippingDiscountCode) {
        this(weddingId, idempotencyKey, discountCode, paymentMethod, shipping, shippingAddressId,
                expectedTotalAmount, shippingQuoteId, shippingDiscountCode, null, null);
    }

    /** Compatibility constructor for the original single-voucher request shape. */
    public CreateOrderRequest(UUID weddingId, String idempotencyKey, String discountCode,
                              PaymentMethod paymentMethod, ShippingAddressRequest shipping,
                              UUID shippingAddressId, Long expectedTotalAmount, UUID shippingQuoteId) {
        this(weddingId, idempotencyKey, discountCode, paymentMethod, shipping, shippingAddressId,
                expectedTotalAmount, shippingQuoteId, null);
    }

    /** Compatibility constructor for older internal callers; service validation still applies. */
    public CreateOrderRequest(UUID weddingId, String idempotencyKey, String discountCode) {
        this(weddingId, idempotencyKey, discountCode, PaymentMethod.COD, null, null, null, null, null);
    }

    public CreateOrderRequest(UUID weddingId, String idempotencyKey, String discountCode,
                              PaymentMethod paymentMethod, ShippingAddressRequest shipping) {
        this(weddingId, idempotencyKey, discountCode, paymentMethod, shipping, null, null, null, null);
    }

    public CreateOrderRequest(UUID weddingId, String idempotencyKey, String discountCode,
                              PaymentMethod paymentMethod, ShippingAddressRequest shipping,
                              UUID shippingAddressId) {
        this(weddingId, idempotencyKey, discountCode, paymentMethod, shipping, shippingAddressId, null, null, null);
    }

    public CreateOrderRequest(UUID weddingId, String idempotencyKey, String discountCode,
                              PaymentMethod paymentMethod, ShippingAddressRequest shipping,
                              UUID shippingAddressId, Long expectedTotalAmount) {
        this(weddingId, idempotencyKey, discountCode, paymentMethod, shipping, shippingAddressId,
                expectedTotalAmount, null, null);
    }
}
