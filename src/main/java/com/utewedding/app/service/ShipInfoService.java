package com.utewedding.app.service;

import com.utewedding.app.dto.request.SavedAddressRequest;
import com.utewedding.app.dto.response.ShippingAddressResponse;
import java.util.List;
import java.util.UUID;

public interface ShipInfoService {
    List<ShippingAddressResponse> list(UUID userId);
    ShippingAddressResponse create(UUID userId, SavedAddressRequest request);
    ShippingAddressResponse update(UUID userId, UUID addressId, SavedAddressRequest request);
    void setDefault(UUID userId, UUID addressId);
    void delete(UUID userId, UUID addressId);
    ShippingAddressResponse getOwned(UUID userId, UUID addressId);
}
