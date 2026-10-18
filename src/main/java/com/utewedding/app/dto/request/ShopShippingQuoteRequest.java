package com.utewedding.app.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** A shop selector is only a hint; the server re-resolves it against the signed-in user's cart. */
public record ShopShippingQuoteRequest(
        @NotNull UUID shopId,
        @NotBlank @Size(max = 64) String selectionFingerprint,
        @NotNull @Valid ShippingAddressRequest destination) {
}
