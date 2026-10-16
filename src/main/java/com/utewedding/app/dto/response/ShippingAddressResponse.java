package com.utewedding.app.dto.response;

import java.util.UUID;

/** Immutable checkout address snapshot; never exposes the ShipInfo entity. */
public record ShippingAddressResponse(
        UUID id,
        String label,
        String recipientName,
        String phone,
        String addressLine,
        String ward,
        String district,
        String province,
        String provinceCode,
        String provinceName,
        String wardCode,
        String wardName,
        boolean defaultAddress,
        String catalogSource) {
    public ShippingAddressResponse(UUID id, String label, String recipientName, String phone,
                                   String addressLine, String ward, String district, String province,
                                   String provinceCode, String provinceName, String wardCode, String wardName,
                                   boolean defaultAddress) {
        this(id, label, recipientName, phone, addressLine, ward, district, province, provinceCode,
                provinceName, wardCode, wardName, defaultAddress, "LOCAL");
    }
    public ShippingAddressResponse(String recipientName, String phone, String addressLine,
                                   String ward, String district, String province) {
        this(null, null, recipientName, phone, addressLine, ward, district, province,
                null, province, null, ward, false, "LOCAL");
    }
}
