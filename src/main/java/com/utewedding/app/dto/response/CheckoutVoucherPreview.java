package com.utewedding.app.dto.response;

import java.util.UUID;
import java.time.Instant;

public record CheckoutVoucherPreview(boolean valid, String message,
                                     CheckoutPricingPreview pricing,
                                     UUID shippingQuoteId, Instant shippingQuoteExpiresAt) {
}
