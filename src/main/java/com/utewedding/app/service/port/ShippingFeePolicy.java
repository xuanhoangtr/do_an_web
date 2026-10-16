package com.utewedding.app.service.port;

import com.utewedding.app.dto.request.ShippingAddressRequest;

/** Calculates shipping from policy/configuration, never from checkout literals. */
public interface ShippingFeePolicy {
    long calculate(ShippingAddressRequest address, long merchandiseAmount);
}
