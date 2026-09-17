package com.utewedding.app.exception;

import com.utewedding.app.dto.response.CheckoutPricingPreview;

public class CheckoutPriceChangedException extends RuntimeException {
    private final CheckoutPricingPreview pricing;

    public CheckoutPriceChangedException(CheckoutPricingPreview pricing) {
        super("Giá giỏ hàng đã thay đổi. Vui lòng kiểm tra tổng tiền mới trước khi đặt hàng.");
        this.pricing = pricing;
    }

    public CheckoutPricingPreview getPricing() {
        return pricing;
    }
}
