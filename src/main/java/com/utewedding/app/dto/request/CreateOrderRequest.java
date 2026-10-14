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
        UUID shippingQuoteId) {

    /** Compatibility constructor for older internal callers; service validation still applies. */
    public CreateOrderRequest(UUID weddingId, String idempotencyKey, String discountCode) {
        this(weddingId, idempotencyKey, discountCode, PaymentMethod.COD, null, null, null, null);
    }

    public CreateOrderRequest(UUID weddingId, String idempotencyKey, String discountCode,
                              PaymentMethod paymentMethod, ShippingAddressRequest shipping) {
        this(weddingId, idempotencyKey, discountCode, paymentMethod, shipping, null, null, null);
    }

    public CreateOrderRequest(UUID weddingId, String idempotencyKey, String discountCode,
                              PaymentMethod paymentMethod, ShippingAddressRequest shipping,
                              UUID shippingAddressId) {
        this(weddingId, idempotencyKey, discountCode, paymentMethod, shipping, shippingAddressId, null, null);
    }

    public CreateOrderRequest(UUID weddingId, String idempotencyKey, String discountCode,
                              PaymentMethod paymentMethod, ShippingAddressRequest shipping,
                              UUID shippingAddressId, Long expectedTotalAmount) {
        this(weddingId, idempotencyKey, discountCode, paymentMethod, shipping, shippingAddressId,
                expectedTotalAmount, null);
    }
}
