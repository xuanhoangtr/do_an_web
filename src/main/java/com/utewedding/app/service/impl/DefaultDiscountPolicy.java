package com.utewedding.app.service.impl;

import com.utewedding.app.service.port.DiscountPolicy;

/**
 * Safe fallback for focused/unit-test wiring. Production injects
 * {@link VoucherDiscountPolicy}; this fallback never grants an ad-hoc discount.
 */
public class DefaultDiscountPolicy implements DiscountPolicy {

    @Override
    public DiscountResult calculate(String discountCode, long eligibleAmount) {
        if (discountCode == null || discountCode.isBlank() || eligibleAmount <= 0) {
            return DiscountResult.none();
        }
        return DiscountResult.none();
    }
}
