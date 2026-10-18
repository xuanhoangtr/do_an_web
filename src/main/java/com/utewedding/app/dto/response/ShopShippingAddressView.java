package com.utewedding.app.dto.response;

import com.utewedding.app.domain.ShopAddressKind;
import java.time.Instant;
import java.util.UUID;

public record ShopShippingAddressView(
        UUID id,
        ShopAddressKind kind,
        int revision,
        String catalogSource,
        String provinceCode,
        String provinceName,
        String wardCode,
        String wardName,
        String addressLine,
        String contactName,
        String contactPhone,
        Instant createdAt) { }
