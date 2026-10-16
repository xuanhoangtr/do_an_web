package com.utewedding.app.service.impl;

import com.utewedding.app.domain.entity.ShipInfoEntity;
import com.utewedding.app.dto.request.SavedAddressRequest;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import com.utewedding.app.dto.response.ShippingAddressResponse;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ShipInfoRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.AdministrativeAddressService;
import com.utewedding.app.service.ShipInfoService;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShipInfoServiceImpl implements ShipInfoService {
    private static final int MAX_SAVED_ADDRESSES = 10;
    private final ShipInfoRepository repository;
    private final UserRepository userRepository;
    private final AdministrativeAddressService addressDirectory;

    public ShipInfoServiceImpl(ShipInfoRepository repository, UserRepository userRepository) {
        this(repository, userRepository, null);
    }

    @Autowired
    public ShipInfoServiceImpl(ShipInfoRepository repository, UserRepository userRepository,
                               AdministrativeAddressService addressDirectory) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.addressDirectory = addressDirectory;
    }

    @Override @Transactional(readOnly = true)
    public List<ShippingAddressResponse> list(UUID userId) {
        return repository.findTop10ByUserIdOrderByDefaultAddressDescCreatedAtDesc(userId)
                .stream().map(this::toResponse).toList();
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public ShippingAddressResponse create(UUID userId, SavedAddressRequest request) {
        requireRequest(request);
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        long addressCount = repository.countByUserId(userId);
        if (addressCount >= MAX_SAVED_ADDRESSES) {
            throw new BusinessConflictException("Mỗi tài khoản chỉ được lưu tối đa 10 địa chỉ giao hàng.");
        }
        ShipInfoEntity entity = new ShipInfoEntity();
        entity.setUserId(userId);
        apply(entity, request);
        if (request.defaultAddress() || addressCount == 0) {
            makeDefault(userId, entity);
        }
        return toResponse(repository.save(entity));
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public ShippingAddressResponse update(UUID userId, UUID addressId, SavedAddressRequest request) {
        requireRequest(request);
        ShipInfoEntity entity = owned(userId, addressId);
        boolean wasDefault = entity.isDefaultAddress();
        apply(entity, request);
        if (request.defaultAddress() || wasDefault) makeDefault(userId, entity);
        return toResponse(repository.save(entity));
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public void setDefault(UUID userId, UUID addressId) {
        ShipInfoEntity entity = owned(userId, addressId);
        makeDefault(userId, entity);
        repository.save(entity);
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public void delete(UUID userId, UUID addressId) {
        ShipInfoEntity entity = owned(userId, addressId);
        repository.delete(entity);
        if (entity.isDefaultAddress()) {
            repository.findTop10ByUserIdOrderByDefaultAddressDescCreatedAtDesc(userId).stream()
                    .filter(next -> !next.getId().equals(addressId))
                    .findFirst().ifPresent(next -> { next.setDefaultAddress(true); repository.save(next); });
        }
    }

    @Override @Transactional(readOnly = true)
    public ShippingAddressResponse getOwned(UUID userId, UUID addressId) {
        return toResponse(owned(userId, addressId));
    }

    private ShipInfoEntity owned(UUID userId, UUID addressId) {
        return repository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy địa chỉ giao hàng."));
    }

    private void makeDefault(UUID userId, ShipInfoEntity entity) {
        repository.clearDefaultForUser(userId);
        entity.setDefaultAddress(true);
    }

    private void apply(ShipInfoEntity entity, SavedAddressRequest request) {
        ShippingAddressRequest address = request.address();
        entity.setLabel(trim(request.label()));
        entity.setRecipientName(trim(address.recipientName()));
        entity.setPhone(trim(address.phone()));
        entity.setAddressLine(trim(address.addressLine()));
        if (addressDirectory == null) {
            // Only the old two-argument unit-test constructor reaches this path.
            entity.setWard(trim(address.ward()));
            entity.setDistrict(trim(address.district()));
            entity.setProvince(trim(address.province()));
            return;
        }
        AdministrativeAddressService.ResolvedAddress resolved =
                addressDirectory.resolve(address.provinceCode(), address.wardCode());
        entity.setProvinceCode(resolved.provinceCode());
        entity.setProvinceName(resolved.provinceName());
        entity.setWardCode(resolved.wardCode());
        entity.setWardName(resolved.wardName());
        entity.setAddressCatalogSource(resolved.catalogSource());
        // Keep legacy display columns populated for old readers.
        entity.setProvince(resolved.provinceName());
        entity.setWard(resolved.wardName());
        entity.setDistrict(null);
    }

    private void requireRequest(SavedAddressRequest request) {
        if (request == null || request.address() == null) {
            throw new IllegalArgumentException("Thông tin địa chỉ là bắt buộc.");
        }
    }

    private ShippingAddressResponse toResponse(ShipInfoEntity entity) {
        return new ShippingAddressResponse(entity.getId(), entity.getLabel(), entity.getRecipientName(),
                entity.getPhone(), entity.getAddressLine(), entity.getWard(), entity.getDistrict(),
                entity.getProvince(), entity.getProvinceCode(), entity.getProvinceName(),
                entity.getWardCode(), entity.getWardName(), entity.isDefaultAddress(),
                entity.getAddressCatalogSource());
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
