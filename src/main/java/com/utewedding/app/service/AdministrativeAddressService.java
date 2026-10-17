package com.utewedding.app.service;

import com.utewedding.app.dto.response.ProvinceResponse;
import com.utewedding.app.dto.response.WardResponse;
import java.util.List;

public interface AdministrativeAddressService {
    String LOCAL = "LOCAL";
    String GHN_STAGING = "GHN_STAGING";

    List<ProvinceResponse> provinces();
    List<WardResponse> wards(String provinceCode);
    ResolvedAddress resolve(String provinceCode, String wardCode);
    ResolvedAddress resolve(String catalogSource, String provinceCode, String wardCode);
    String activeCatalogSource();

    record ResolvedAddress(String provinceCode, String provinceName, String wardCode, String wardName,
                           String catalogSource) {
        public ResolvedAddress(String provinceCode, String provinceName, String wardCode, String wardName) {
            this(provinceCode, provinceName, wardCode, wardName, LOCAL);
        }
    }
}
