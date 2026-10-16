package com.utewedding.app.service.impl;

import com.utewedding.app.dto.request.ShippingAddressRequest;
import com.utewedding.app.service.port.ShippingFeePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Initial policy is configurable free shipping; pricing can later be moved to carrier quotes. */
@Service
public class DefaultShippingFeePolicy implements ShippingFeePolicy {
    private final long baseFee;

    public DefaultShippingFeePolicy(@Value("${shipping.base-fee:0}") long baseFee) {
        if (baseFee < 0) throw new IllegalArgumentException("shipping.base-fee must not be negative");
        this.baseFee = baseFee;
    }

    @Override
    public long calculate(ShippingAddressRequest address, long merchandiseAmount) {
        return baseFee;
    }
}
