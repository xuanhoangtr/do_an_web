package com.utewedding.app.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public record SavedAddressRequest(
        @Size(max = 60) String label,
        @Valid ShippingAddressRequest address,
        boolean defaultAddress) {
}
