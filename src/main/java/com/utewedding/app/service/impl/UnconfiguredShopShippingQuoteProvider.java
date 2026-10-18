package com.utewedding.app.service.impl;

import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.service.port.ShopShippingQuoteProvider;
import com.utewedding.app.service.port.ShippingProvider;

/** Fail-closed until a secret-store-backed per-shop GHN adapter is configured. */
public class UnconfiguredShopShippingQuoteProvider implements ShopShippingQuoteProvider {
    @Override
    public ShippingProvider.Quote quote(Request request) {
        throw new BusinessConflictException(
                "Chưa kết nối bộ xác thực GHN riêng cho cửa hàng; chưa thể lấy báo giá vận chuyển.");
    }
}
