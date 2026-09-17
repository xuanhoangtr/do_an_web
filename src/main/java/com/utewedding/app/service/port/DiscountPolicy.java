package com.utewedding.app.service.port;

import java.util.UUID;
import com.utewedding.app.domain.VoucherType;

/** Pricing policy is injected so campaigns can move out of business services. */
public interface DiscountPolicy {
    DiscountResult calculate(String discountCode, long eligibleAmount);

    default DiscountResult calculateForUser(String discountCode, long eligibleAmount, UUID userId) {
        return calculate(discountCode, eligibleAmount);
    }

    default DiscountResult previewForUser(String discountCode, long eligibleAmount, UUID userId) {
        return calculate(discountCode, eligibleAmount);
    }

    default DiscountResult calculateForCheckout(String discountCode, long eligibleAmount,
                                                long customerShippingFee, String shippingProviderCode,
                                                UUID userId) {
        return calculateForUser(discountCode, eligibleAmount, userId);
    }

    default DiscountResult previewForCheckout(String discountCode, long eligibleAmount,
                                              long customerShippingFee, String shippingProviderCode,
                                              UUID userId) {
        return previewForUser(discountCode, eligibleAmount, userId);
    }

    default void reserve(String discountCode, UUID userId, UUID orderId, long discountAmount) {
        // Legacy/focused policies do not persist usage reservations.
    }

    default void release(UUID orderId) {
        // Legacy/focused policies do not persist usage reservations.
    }

    record DiscountResult(long amount, String appliedCode, VoucherType type) {
        public DiscountResult(long amount, String appliedCode) {
            this(amount, appliedCode, appliedCode == null ? null : VoucherType.FIXED);
        }
        public static DiscountResult none() { return new DiscountResult(0, null, null); }
    }
}
