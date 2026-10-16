package com.utewedding.app.exception;

public class ShippingQuoteExpiredException extends BusinessConflictException {
    public ShippingQuoteExpiredException() {
        super("Phí giao hàng cần được cập nhật. Vui lòng xác nhận lại đơn hàng.");
    }
}
