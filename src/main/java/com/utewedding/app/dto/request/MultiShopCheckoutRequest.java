package com.utewedding.app.dto.request;

import com.utewedding.app.domain.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Row-addressed checkout payload used by the grouped checkout API. */
public record MultiShopCheckoutRequest(
        @NotEmpty @Size(max = 100) List<@NotNull UUID> selectedCartItemIds,
        UUID shippingAddressId,
        @Valid ShippingAddressRequest shipping,
        @NotNull PaymentMethod paymentMethod,
        @NotEmpty @Size(max = 20) List<@NotNull @Valid ShopOptions> shops,
        @Size(max = 120) String idempotencyKey,
        @Size(max = 64) String previewRevision,
        Long expectedTotalAmount) {

    public MultiShopCheckoutRequest {
        selectedCartItemIds = selectedCartItemIds == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(selectedCartItemIds));
        shops = shops == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(shops));
    }

    public record ShopOptions(
            @NotNull UUID shopId,
            @Size(max = 80) String discountCode,
            @Size(max = 80) String shippingDiscountCode,
            @Size(max = 500) String customerNote,
            UUID shippingQuoteId) {
    }
}
