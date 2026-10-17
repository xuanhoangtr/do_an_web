package com.utewedding.app.service.impl;

import com.utewedding.app.dto.response.ProvinceResponse;
import com.utewedding.app.dto.response.WardResponse;
import com.utewedding.app.domain.entity.AdministrativeAddressCatalogStateEntity;
import com.utewedding.app.repository.AdministrativeAddressCatalogStateRepository;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ProvinceRepository;
import com.utewedding.app.repository.WardRepository;
import com.utewedding.app.service.AdministrativeAddressService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministrativeAddressServiceImpl implements AdministrativeAddressService {
    private final ProvinceRepository provinces;
    private final WardRepository wards;
    private final AdministrativeAddressCatalogStateRepository catalogState;

    public AdministrativeAddressServiceImpl(ProvinceRepository provinces, WardRepository wards) {
        this(provinces, wards, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdministrativeAddressServiceImpl(ProvinceRepository provinces, WardRepository wards,
            AdministrativeAddressCatalogStateRepository catalogState) {
        this.provinces = provinces;
        this.wards = wards;
        this.catalogState = catalogState;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProvinceResponse> provinces() {
        String source = activeCatalogSource();
        return provinces.findByCatalogSourceAndActiveTrueOrderByNameAsc(source).stream()
                .map(item -> new ProvinceResponse(item.getCode(), item.getName(), item.getFullName(), source))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WardResponse> wards(String provinceCode) {
        String code = normalize(provinceCode);
        String source = activeCatalogSource();
        if (code == null || provinces.findByCodeAndCatalogSourceAndActiveTrue(code, source).isEmpty()) {
            throw new ResourceNotFoundException("Tỉnh/thành không tồn tại trong danh mục hiện hành.");
        }
        return wards.findByCatalogSourceAndProvinceCodeAndActiveTrueOrderByNameAsc(source, code).stream()
                .map(item -> new WardResponse(item.getCode(), item.getName(), item.getFullName(),
                        item.getProvinceCode(), source))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedAddress resolve(String provinceCode, String wardCode) {
        return resolve(activeCatalogSource(), provinceCode, wardCode);
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedAddress resolve(String catalogSource, String provinceCode, String wardCode) {
        String source = normalize(catalogSource);
        if (!AdministrativeAddressService.LOCAL.equals(source)
                && !AdministrativeAddressService.GHN_STAGING.equals(source)) {
            throw new BusinessConflictException("Nguồn danh mục địa chỉ không hợp lệ.");
        }
        String province = normalize(provinceCode);
        String ward = normalize(wardCode);
        if (province == null || ward == null) {
            throw new BusinessConflictException("Vui lòng chọn tỉnh/thành và phường/xã.");
        }
        var provinceEntity = provinces.findByCodeAndCatalogSourceAndActiveTrue(province, source)
                .orElseThrow(() -> new ResourceNotFoundException("Tỉnh/thành không tồn tại."));
        var wardEntity = wards.findByCodeAndProvinceCodeAndCatalogSourceAndActiveTrue(ward, province, source)
                .orElse(null);
        if (wardEntity == null) {
            throw new BusinessConflictException("Phường/xã không thuộc tỉnh/thành đã chọn.");
        }
        return new ResolvedAddress(provinceEntity.getCode(), provinceEntity.getName(),
                wardEntity.getCode(), wardEntity.getName(), source);
    }

    @Override
    @Transactional(readOnly = true)
    public String activeCatalogSource() {
        if (catalogState == null) return AdministrativeAddressService.LOCAL;
        return catalogState.findById((short) 1).map(AdministrativeAddressCatalogStateEntity::getActiveSource)
                .filter(value -> AdministrativeAddressService.GHN_STAGING.equals(value))
                .orElse(AdministrativeAddressService.LOCAL);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
