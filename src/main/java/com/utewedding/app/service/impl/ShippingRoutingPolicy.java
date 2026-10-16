package com.utewedding.app.service.impl;

import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.service.AdministrativeAddressService;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Source-aware destination routing shared by platform and shop-scoped quotes. */
public final class ShippingRoutingPolicy {
    public static final int CURRENT_POLICY_VERSION = 1;
    public static final String PRICING_FREE = "FREE";
    public static final String PRICING_PROVIDER_QUOTE = "PROVIDER_QUOTE";

    private final Set<String> localInternalProvinceCodes;
    private final String ghnStagingInternalProvinceId;

    public ShippingRoutingPolicy(String localInternalProvinceCodes, String ghnStagingInternalProvinceId) {
        this.localInternalProvinceCodes = Arrays.stream(
                        (localInternalProvinceCodes == null ? "79" : localInternalProvinceCodes).split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        this.ghnStagingInternalProvinceId = ghnStagingInternalProvinceId == null
                || ghnStagingInternalProvinceId.isBlank() ? "1000001" : ghnStagingInternalProvinceId.trim();
    }

    public Decision decide(String catalogSource, String provinceCode) {
        String source = catalogSource == null ? "" : catalogSource.trim().toUpperCase(java.util.Locale.ROOT);
        String province = provinceCode == null ? "" : provinceCode.trim();
        if (province.isBlank()) {
            throw new BusinessConflictException("Không xác định được tỉnh/thành nhận hàng hiện hành.");
        }

        boolean internal = switch (source) {
            case AdministrativeAddressService.LOCAL -> localInternalProvinceCodes.contains(province);
            case AdministrativeAddressService.GHN_STAGING -> ghnStagingInternalProvinceId.equals(province);
            default -> throw new BusinessConflictException("Nguồn danh mục địa chỉ không hợp lệ.");
        };
        return internal
                ? new Decision("HCM", "INTERNAL", PRICING_FREE, "STANDARD", "INTERNAL_STANDARD",
                        CURRENT_POLICY_VERSION)
                : new Decision("OUTSIDE_HCM", "GHN", PRICING_PROVIDER_QUOTE, "STANDARD", "STANDARD",
                        CURRENT_POLICY_VERSION);
    }

    public record Decision(String coverageScope, String providerCode, String pricingMode,
                           String methodCode, String serviceCode, int policyVersion) { }
}
