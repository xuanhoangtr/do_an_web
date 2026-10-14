package com.utewedding.app.domain;

public enum VoucherType {
    PERCENT,
    FIXED,
    SHIPPING_FIXED,
    SHIPPING_FREE;

    public boolean isShippingVoucher() {
        return this == SHIPPING_FIXED || this == SHIPPING_FREE;
    }
}
