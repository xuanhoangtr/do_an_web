package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ShopAddressKind;
import com.utewedding.app.domain.entity.ShopAddressHeadEntity;
import com.utewedding.app.domain.entity.ShopAddressRevisionEntity;
import com.utewedding.app.domain.entity.ShopShippingBindingEntity;
import com.utewedding.app.domain.entity.ShopShippingRouteEntity;
import com.utewedding.app.dto.request.ShopShippingAddressForm;
import com.utewedding.app.dto.response.ShopShippingAddressView;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.ShopAddressHeadRepository;
import com.utewedding.app.repository.ShopAddressRevisionRepository;
import com.utewedding.app.repository.ShopShippingBindingRepository;
import com.utewedding.app.repository.ShopShippingRouteRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.AdministrativeAddressService;
import com.utewedding.app.service.TextSanitizer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Vendor-owned pickup/return address revisions. Address history is never rewritten. */
@Service
public class ShopShippingAddressService {
    private final VendorWorkspaceContext workspace;
    private final ShopAccessPolicy access;
    private final ShopAddressHeadRepository heads;
    private final ShopAddressRevisionRepository revisions;
    private final ShopShippingBindingRepository bindings;
    private final ShopShippingRouteRepository routes;
    private final AdministrativeAddressService addresses;
    private final TextSanitizer sanitizer;

    public ShopShippingAddressService(VendorWorkspaceContext workspace, ShopAccessPolicy access,
                                      ShopAddressHeadRepository heads, ShopAddressRevisionRepository revisions,
                                      ShopShippingBindingRepository bindings, ShopShippingRouteRepository routes,
                                      AdministrativeAddressService addresses, TextSanitizer sanitizer) {
        this.workspace = workspace; this.access = access; this.heads = heads; this.revisions = revisions;
        this.bindings = bindings; this.routes = routes; this.addresses = addresses; this.sanitizer = sanitizer;
    }

    @Transactional(readOnly = true)
    public Map<ShopAddressKind, ShopShippingAddressView> currentForVendor(UserPrincipal actor) {
        UUID shopId = workspace.requireWorkspace(actor).shopId();
        return currentForShop(shopId);
    }

    @Transactional(readOnly = true)
    private Map<ShopAddressKind, ShopShippingAddressView> currentForShop(UUID shopId) {
        Map<ShopAddressKind, ShopShippingAddressView> result = new EnumMap<>(ShopAddressKind.class);
        for (ShopAddressKind kind : ShopAddressKind.values()) {
            heads.findByShopIdAndAddressKind(shopId, kind)
                    .flatMap(head -> revisions.findByIdAndShopIdAndAddressKind(head.getRevisionId(), shopId, kind))
                    .map(ShopShippingAddressService::view)
                    .ifPresent(value -> result.put(kind, value));
        }
        return Map.copyOf(result);
    }

    @Transactional(rollbackFor = Exception.class)
    public ShopShippingAddressView save(UserPrincipal actor, ShopShippingAddressForm form) {
        if (form == null || form.getAddressKind() == null) {
            throw new BusinessConflictException("Thông tin địa chỉ giao nhận không hợp lệ.");
        }
        UUID shopId = workspace.requireWorkspace(actor).shopId();
        access.requireMutation(actor, shopId);
        ShopAddressKind kind = form.getAddressKind();
        ShopAddressHeadEntity head = heads.findForUpdate(shopId, kind).orElse(null);
        int currentRevision = head == null ? 0 : revisions
                .findByIdAndShopIdAndAddressKind(head.getRevisionId(), shopId, kind)
                .map(ShopAddressRevisionEntity::getRevision).orElse(0);
        if (form.getExpectedRevision() != currentRevision) {
            throw new BusinessConflictException("Địa chỉ vừa được cập nhật ở nơi khác. Vui lòng tải lại trang.");
        }
        if (!addresses.activeCatalogSource().equals(form.getCatalogSource())) {
            throw new BusinessConflictException("Danh mục địa chỉ vừa đổi. Tải lại trang rồi chọn địa chỉ mới.");
        }
        String line = sanitizer.trimToNull(form.getAddressLine(), 500);
        String name = sanitizer.trimToNull(form.getContactName(), 160);
        String phone = sanitizer.trimToNull(form.getContactPhone(), 30);
        if (line == null || name == null || phone == null || !phone.matches("^[+0-9() .-]{7,30}$")) {
            throw new BusinessConflictException("Vui lòng nhập địa chỉ, người liên hệ và số điện thoại hợp lệ.");
        }
        AdministrativeAddressService.ResolvedAddress canonical = addresses.resolve(
                form.getCatalogSource(), form.getProvinceCode(), form.getWardCode());
        ShopAddressRevisionEntity revision = revisions.saveAndFlush(new ShopAddressRevisionEntity(shopId, kind,
                currentRevision + 1, canonical.catalogSource(), canonical.provinceCode(), canonical.provinceName(),
                canonical.wardCode(), canonical.wardName(), line, name, phone, actor.getId()));
        if (head == null) heads.save(new ShopAddressHeadEntity(shopId, kind, revision.getId()));
        else head.setRevisionId(revision.getId());

        // Preserve old verification evidence, but deactivate bindings/routes whose
        // stored address revision is no longer the current shop address.
        for (ShopShippingBindingEntity binding : bindings.findByShopIdOrderByRevisionDesc(shopId)) {
            if (binding.isActive()) {
                binding.setActive(false);
                for (ShopShippingRouteEntity route : routes.findByBindingIdAndShopIdOrderByProvinceNameAscWardNameAsc(
                        binding.getId(), shopId)) {
                    route.setActive(false);
                }
            }
        }
        return view(revision);
    }

    private static ShopShippingAddressView view(ShopAddressRevisionEntity row) {
        return new ShopShippingAddressView(row.getId(), row.getAddressKind(), row.getRevision(),
                row.getCatalogSource(), row.getProvinceCode(), row.getProvinceName(), row.getWardCode(),
                row.getWardName(), row.getAddressLine(), row.getContactName(), row.getContactPhone(), row.getCreatedAt());
    }
}
