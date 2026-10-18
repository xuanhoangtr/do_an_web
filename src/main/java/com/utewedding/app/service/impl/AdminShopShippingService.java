package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ShopAddressKind;
import com.utewedding.app.domain.ShopKind;
import com.utewedding.app.domain.entity.ShopAddressHeadEntity;
import com.utewedding.app.domain.entity.ShopAddressRevisionEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.domain.entity.ShippingProviderAccountEntity;
import com.utewedding.app.domain.entity.ShopShippingBindingEntity;
import com.utewedding.app.domain.entity.ShopShippingRouteEntity;
import com.utewedding.app.dto.request.ShippingProviderAccountForm;
import com.utewedding.app.dto.request.ShopShippingAddressForm;
import com.utewedding.app.dto.request.ShopShippingBindingForm;
import com.utewedding.app.dto.request.ShopShippingRouteForm;
import com.utewedding.app.dto.response.AdminShopShippingView;
import com.utewedding.app.dto.response.ShopShippingAddressView;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ShopAddressHeadRepository;
import com.utewedding.app.repository.ShopAddressRevisionRepository;
import com.utewedding.app.repository.ShopRepository;
import com.utewedding.app.repository.ShippingProviderAccountRepository;
import com.utewedding.app.repository.ShopShippingBindingRepository;
import com.utewedding.app.repository.ShopShippingRouteRepository;
import com.utewedding.app.security.UserPrincipal;
import com.utewedding.app.service.AdministrativeAddressService;
import com.utewedding.app.service.TextSanitizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin-maintained GHN staging records. This service never calls the carrier API. */
@Service
public class AdminShopShippingService {
    private static final Pattern CREDENTIAL_REFERENCE = Pattern.compile(
            "^credref:[A-Za-z0-9][A-Za-z0-9._/-]{2,180}$");
    private static final Pattern EXTERNAL_SHOP_ID = Pattern.compile("^[0-9]{1,24}$");
    private final ShopRepository shops;
    private final ShopAddressHeadRepository addressHeads;
    private final ShopAddressRevisionRepository addressRevisions;
    private final ShippingProviderAccountRepository accounts;
    private final ShopShippingBindingRepository bindings;
    private final ShopShippingRouteRepository routes;
    private final AdministrativeAddressService administrativeAddresses;
    private final TextSanitizer sanitizer;
    private final ShippingEvidenceRedactor redactor;

    public AdminShopShippingService(ShopRepository shops, ShopAddressHeadRepository addressHeads,
                                    ShopAddressRevisionRepository addressRevisions,
                                    ShippingProviderAccountRepository accounts,
                                    ShopShippingBindingRepository bindings, ShopShippingRouteRepository routes,
                                    AdministrativeAddressService administrativeAddresses, TextSanitizer sanitizer,
                                    ShippingEvidenceRedactor redactor) {
        this.shops = shops; this.addressHeads = addressHeads; this.addressRevisions = addressRevisions;
        this.accounts = accounts; this.bindings = bindings; this.routes = routes;
        this.administrativeAddresses = administrativeAddresses; this.sanitizer = sanitizer; this.redactor = redactor;
    }

    @Transactional(readOnly = true)
    public AdminShopShippingView view(UserPrincipal actor, UUID shopId) {
        requireAdmin(actor);
        ShopEntity shop = requireVendorShop(shopId);
        Map<ShopAddressKind, ShopShippingAddressView> foundAddresses = currentAddresses(shopId);
        Map<String, ShopShippingAddressView> addressMap = new java.util.LinkedHashMap<>();
        foundAddresses.forEach((kind, value) -> addressMap.put(kind.name(), value));
        List<AdminShopShippingView.AccountCard> accountCards = accounts
                .findByProviderCodeAndEnvironmentOrderByAccountNameAsc("GHN", "STAGING").stream()
                .map(account -> new AdminShopShippingView.AccountCard(account.getId(), account.getProviderCode(),
                        account.getEnvironment(), account.getAccountName(), account.getStatus(),
                        "Đã lưu tham chiếu ngoài hệ thống", account.getVerificationEvidenceRedacted(),
                        account.getVerifiedAt()))
                .toList();
        List<AdminShopShippingView.BindingCard> bindingCards = new ArrayList<>();
        for (ShopShippingBindingEntity binding : bindings.findByShopIdOrderByRevisionDesc(shopId)) {
            ShippingProviderAccountEntity account = accounts.findById(binding.getProviderAccountId()).orElse(null);
            int pickupRevision = addressRevisions.findByIdAndShopIdAndAddressKind(
                    binding.getPickupAddressRevisionId(), shopId, ShopAddressKind.PICKUP)
                    .map(ShopAddressRevisionEntity::getRevision).orElse(0);
            int returnRevision = addressRevisions.findByIdAndShopIdAndAddressKind(
                    binding.getReturnAddressRevisionId(), shopId, ShopAddressKind.RETURN)
                    .map(ShopAddressRevisionEntity::getRevision).orElse(0);
            List<AdminShopShippingView.RouteCard> routeCards = routes
                    .findByBindingIdAndShopIdOrderByProvinceNameAscWardNameAsc(binding.getId(), shopId).stream()
                    .map(route -> new AdminShopShippingView.RouteCard(route.getId(), route.getCatalogSource(),
                            route.getProvinceCode(), route.getProvinceName(), route.getWardCode(), route.getWardName(),
                            route.getStatus(), route.isActive(), route.getEvidenceRedacted(), route.getVerifiedAt()))
                    .toList();
            bindingCards.add(new AdminShopShippingView.BindingCard(binding.getId(), binding.getRevision(),
                    binding.getProviderAccountId(), account == null ? "Không xác định" : account.getAccountName(),
                    account == null ? "UNKNOWN" : account.getStatus(), redactor.maskIdentifier(binding.getExternalShopId()),
                    routeMode(binding), pickupRevision, returnRevision,
                    "VERIFIED".equals(binding.getStatus()) && !binding.isActive()
                            ? "VERIFIED_OLD_ADDRESS" : binding.getStatus(), binding.isActive(),
                    binding.isRouteCoverageVerified(), binding.isCodBeneficiaryVerified(),
                    binding.getEvidenceRedacted(), binding.getCodBeneficiaryEvidenceRedacted(), routeCards,
                    binding.getVerifiedAt()));
        }
        return new AdminShopShippingView(shop.getId(), shop.getDisplayName(), addressMap, accountCards, bindingCards);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveAddress(UserPrincipal actor, UUID shopId, ShopShippingAddressForm form) {
        requireAdmin(actor);
        if (form == null || form.getAddressKind() == null) {
            throw new BusinessConflictException("Thông tin địa chỉ giao nhận không hợp lệ.");
        }
        requireVendorShopForUpdate(shopId);
        ShopAddressKind kind = form.getAddressKind();
        ShopAddressHeadEntity head = addressHeads.findForUpdate(shopId, kind).orElse(null);
        int currentRevision = head == null ? 0 : addressRevisions
                .findByIdAndShopIdAndAddressKind(head.getRevisionId(), shopId, kind)
                .map(ShopAddressRevisionEntity::getRevision).orElse(0);
        if (form.getExpectedRevision() != currentRevision) {
            throw new BusinessConflictException("Địa chỉ vừa được cập nhật ở nơi khác. Vui lòng tải lại trang.");
        }
        if (!administrativeAddresses.activeCatalogSource().equals(form.getCatalogSource())) {
            throw new BusinessConflictException("Danh mục địa chỉ vừa đổi. Tải lại trang rồi chọn địa chỉ mới.");
        }
        String line = sanitizer.trimToNull(form.getAddressLine(), 500);
        String name = sanitizer.trimToNull(form.getContactName(), 160);
        String phone = sanitizer.trimToNull(form.getContactPhone(), 30);
        if (line == null || name == null || phone == null || !phone.matches("^[+0-9() .-]{7,30}$")) {
            throw new BusinessConflictException("Vui lòng nhập địa chỉ, người liên hệ và số điện thoại hợp lệ.");
        }
        AdministrativeAddressService.ResolvedAddress canonical = administrativeAddresses.resolve(
                form.getCatalogSource(), form.getProvinceCode(), form.getWardCode());
        ShopAddressRevisionEntity revision = addressRevisions.saveAndFlush(new ShopAddressRevisionEntity(shopId,
                kind, currentRevision + 1, canonical.catalogSource(), canonical.provinceCode(),
                canonical.provinceName(), canonical.wardCode(), canonical.wardName(), line, name, phone,
                actor.getId()));
        if (head == null) addressHeads.save(new ShopAddressHeadEntity(shopId, kind, revision.getId()));
        else head.setRevisionId(revision.getId());

        // An address revision changes the verified shipping origin/destination.
        // Keep history, but require every active binding and route to be verified again.
        for (ShopShippingBindingEntity binding : bindings.findByShopIdOrderByRevisionDesc(shopId)) {
            if (binding.isActive()) {
                binding.setActive(false);
                for (ShopShippingRouteEntity route : routes.findByBindingIdAndShopIdOrderByProvinceNameAscWardNameAsc(
                        binding.getId(), shopId)) {
                    route.setActive(false);
                }
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void createStagingAccount(UserPrincipal actor, ShippingProviderAccountForm form) {
        requireAdmin(actor);
        if (form == null) throw new BusinessConflictException("Thông tin tài khoản GHN không hợp lệ.");
        String name = sanitizer.trimToNull(form.getAccountName(), 120);
        String reference = sanitizer.trimToNull(form.getCredentialReference(), 200);
        if (name == null || reference == null || !CREDENTIAL_REFERENCE.matcher(reference).matches()
                || reference.contains("..")) {
            throw new BusinessConflictException("Chỉ nhận mã tham chiếu dạng credref:...; không nhập token GHN.");
        }
        accounts.save(new ShippingProviderAccountEntity("GHN", "STAGING", name, reference, actor.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void verifyStagingAccount(UserPrincipal actor, UUID accountId, boolean confirm, String evidence) {
        requireAdmin(actor);
        if (!confirm) throw new BusinessConflictException("Hãy xác nhận đã kiểm tra tài khoản GHN Staging.");
        ShippingProviderAccountEntity account = requireAccount(accountId);
        if (!"STAGING".equals(account.getEnvironment())) {
            throw new BusinessConflictException("Chỉ cho phép xác minh tài khoản GHN Staging trong đợt này.");
        }
        if ("DISABLED".equals(account.getStatus())) {
            throw new BusinessConflictException("Tài khoản GHN đã bị vô hiệu hóa.");
        }
        account.setStatus("VERIFIED");
        account.setVerificationEvidenceRedacted(requireEvidence(evidence));
        account.setVerifiedBy(actor.getId());
        account.setVerifiedAt(Instant.now());
    }

    @Transactional(rollbackFor = Exception.class)
    public void createBinding(UserPrincipal actor, UUID shopId, ShopShippingBindingForm form) {
        requireAdmin(actor);
        ShopEntity shop = requireVendorShopForUpdate(shopId);
        if (form == null || form.getProviderAccountId() == null) {
            throw new BusinessConflictException("Vui lòng chọn tài khoản GHN Staging.");
        }
        String routeMode = normalizeRouteMode(form.getRouteMode());
        String externalShopId = sanitizer.trimToNull(form.getExternalShopId(), 24);
        if (externalShopId == null || !EXTERNAL_SHOP_ID.matcher(externalShopId).matches()) {
            throw new BusinessConflictException("Mã ShopId GHN phải là dãy số được GHN cấp.");
        }
        ShippingProviderAccountEntity account = requireAccount(form.getProviderAccountId());
        requireVerifiedStagingAccount(account);
        UUID pickupId = currentRevisionId(shopId, ShopAddressKind.PICKUP);
        UUID returnId = currentRevisionId(shopId, ShopAddressKind.RETURN);
        int revision = Math.toIntExact(bindings.countByShopId(shopId) + 1);
        ShopShippingBindingEntity binding = new ShopShippingBindingEntity(shop.getId(), revision, account.getId(),
                externalShopId, pickupId, returnId, actor.getId());
        binding.setRouteMode(routeMode);
        bindings.save(binding);
    }

    @Transactional(rollbackFor = Exception.class)
    public void addRoute(UserPrincipal actor, UUID shopId, UUID bindingId, ShopShippingRouteForm form) {
        requireAdmin(actor);
        if (form == null) throw new BusinessConflictException("Thông tin tuyến giao hàng không hợp lệ.");
        ShopShippingBindingEntity binding = requireBinding(shopId, bindingId, true);
        requireLegacyRoutes(binding);
        requireVerifiedStagingAccount(requireAccount(binding.getProviderAccountId()));
        if ("VERIFIED".equals(binding.getStatus())) {
            throw new BusinessConflictException("Binding đã xác minh; hãy tạo revision mới để thay đổi tuyến.");
        }
        if (!administrativeAddresses.activeCatalogSource().equals(form.getCatalogSource())) {
            throw new BusinessConflictException("Danh mục địa chỉ vừa đổi. Tải lại trang rồi chọn tuyến mới.");
        }
        AdministrativeAddressService.ResolvedAddress destination = administrativeAddresses.resolve(
                form.getCatalogSource(), form.getProvinceCode(), form.getWardCode());
        routes.save(new ShopShippingRouteEntity(binding.getId(), shopId, destination.catalogSource(),
                destination.provinceCode(), destination.provinceName(), destination.wardCode(),
                destination.wardName(), actor.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void verifyRoute(UserPrincipal actor, UUID shopId, UUID bindingId, UUID routeId,
                            boolean confirmCoverage, String evidence) {
        requireAdmin(actor);
        if (!confirmCoverage) throw new BusinessConflictException("Hãy xác nhận đã đối chiếu tuyến trên GHN Staging.");
        ShopShippingBindingEntity binding = requireBinding(shopId, bindingId, true);
        requireLegacyRoutes(binding);
        requireCurrentAddresses(binding, shopId);
        requireVerifiedStagingAccount(requireAccount(binding.getProviderAccountId()));
        ShopShippingRouteEntity route = routes.findForUpdate(routeId, bindingId, shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tuyến giao hàng."));
        route.setStatus("VERIFIED");
        route.setActive(true);
        route.setEvidenceRedacted(requireEvidence(evidence));
        route.setVerifiedBy(actor.getId());
        route.setVerifiedAt(Instant.now());
    }

    @Transactional(rollbackFor = Exception.class)
    public void verifyBinding(UserPrincipal actor, UUID shopId, UUID bindingId,
                              boolean confirmAddressAndCoverage, String evidence) {
        requireAdmin(actor);
        if (!confirmAddressAndCoverage) {
            throw new BusinessConflictException("Hãy xác nhận đã đối chiếu địa chỉ lấy/trả và phạm vi tuyến.");
        }
        ShopShippingBindingEntity binding = requireBinding(shopId, bindingId, true);
        requireCurrentAddresses(binding, shopId);
        requireVerifiedStagingAccount(requireAccount(binding.getProviderAccountId()));
        String routeMode = routeMode(binding);
        if ("LEGACY_ROUTES".equals(routeMode)
                && !routes.existsByBindingIdAndShopIdAndStatus(bindingId, shopId, "VERIFIED")) {
            throw new BusinessConflictException("Cần xác minh ít nhất một tuyến giao hàng trước.");
        }
        if (!"LEGACY_ROUTES".equals(routeMode) && !"API_QUOTE".equals(routeMode)) {
            throw new BusinessConflictException("Chế độ tuyến GHN của binding không hợp lệ.");
        }
        binding.setStatus("VERIFIED");
        binding.setActive(true);
        binding.setRouteCoverageVerified("LEGACY_ROUTES".equals(routeMode));
        binding.setEvidenceRedacted(requireEvidence(evidence));
        binding.setVerifiedBy(actor.getId());
        binding.setVerifiedAt(Instant.now());
        // COD is a separate capability: it remains unavailable until its beneficiary is verified.
        binding.setCodBeneficiaryVerified(false);
        binding.setCodBeneficiaryEvidenceRedacted(null);
        binding.setCodBeneficiaryVerifiedBy(null);
        binding.setCodBeneficiaryVerifiedAt(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void verifyCodBeneficiary(UserPrincipal actor, UUID shopId, UUID bindingId,
                                     boolean confirm, String evidence) {
        requireAdmin(actor);
        if (!confirm) throw new BusinessConflictException("Hãy xác nhận đã kiểm tra bên nhận tiền COD.");
        ShopShippingBindingEntity binding = requireBinding(shopId, bindingId, true);
        if (binding.isCodBeneficiaryVerified()) {
            throw new BusinessConflictException("Bên nhận tiền COD của binding này đã được xác minh.");
        }
        if (!"VERIFIED".equals(binding.getStatus()) || !binding.isActive()) {
            throw new BusinessConflictException("Trước hết hãy xác minh binding GHN.");
        }
        requireVerifiedStagingAccount(requireAccount(binding.getProviderAccountId()));
        binding.setCodBeneficiaryVerified(true);
        binding.setCodBeneficiaryEvidenceRedacted(requireEvidence(evidence));
        binding.setCodBeneficiaryVerifiedBy(actor.getId());
        binding.setCodBeneficiaryVerifiedAt(Instant.now());
    }

    private ShopEntity requireVendorShop(UUID shopId) {
        ShopEntity shop = shops.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cửa hàng."));
        if (shop.getKind() != ShopKind.VENDOR) throw new ResourceNotFoundException("Không tìm thấy cửa hàng.");
        return shop;
    }

    private ShopEntity requireVendorShopForUpdate(UUID shopId) {
        ShopEntity shop = shops.findByIdForUpdate(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cửa hàng."));
        if (shop.getKind() != ShopKind.VENDOR) throw new ResourceNotFoundException("Không tìm thấy cửa hàng.");
        return shop;
    }

    private ShippingProviderAccountEntity requireAccount(UUID accountId) {
        return accounts.findByIdAndProviderCode(accountId, "GHN")
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản GHN."));
    }

    private void requireVerifiedStagingAccount(ShippingProviderAccountEntity account) {
        if (!"STAGING".equals(account.getEnvironment()) || !"VERIFIED".equals(account.getStatus())) {
            throw new BusinessConflictException("Tài khoản GHN Staging chưa được quản trị viên xác minh.");
        }
    }

    private ShopShippingBindingEntity requireBinding(UUID shopId, UUID bindingId, boolean lock) {
        if (lock) requireVendorShopForUpdate(shopId);
        else requireVendorShop(shopId);
        return (lock ? bindings.findByIdAndShopIdForUpdate(bindingId, shopId)
                : bindings.findByIdAndShopId(bindingId, shopId))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cấu hình GHN của cửa hàng."));
    }

    private UUID currentRevisionId(UUID shopId, ShopAddressKind kind) {
        return addressHeads.findByShopIdAndAddressKind(shopId, kind)
                .map(ShopAddressHeadEntity::getRevisionId)
                .filter(id -> addressRevisions.findByIdAndShopIdAndAddressKind(id, shopId, kind).isPresent())
                .orElseThrow(() -> new BusinessConflictException("Cửa hàng cần khai báo địa chỉ lấy hàng và trả hàng trước."));
    }

    private void requireCurrentAddresses(ShopShippingBindingEntity binding, UUID shopId) {
        UUID pickup = currentRevisionId(shopId, ShopAddressKind.PICKUP);
        UUID returns = currentRevisionId(shopId, ShopAddressKind.RETURN);
        if (!pickup.equals(binding.getPickupAddressRevisionId()) || !returns.equals(binding.getReturnAddressRevisionId())) {
            throw new BusinessConflictException("Địa chỉ cửa hàng đã thay đổi. Tạo binding mới rồi xác minh lại.");
        }
    }

    private void requireLegacyRoutes(ShopShippingBindingEntity binding) {
        if (!"LEGACY_ROUTES".equals(routeMode(binding))) {
            throw new BusinessConflictException("Binding API_QUOTE không quản lý tuyến theo phường/xã.");
        }
    }

    private String routeMode(ShopShippingBindingEntity binding) {
        String value = binding.getRouteMode();
        return value == null || value.isBlank() ? "LEGACY_ROUTES" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRouteMode(String value) {
        String normalized = value == null || value.isBlank()
                ? "LEGACY_ROUTES" : value.trim().toUpperCase(Locale.ROOT);
        if (!"LEGACY_ROUTES".equals(normalized) && !"API_QUOTE".equals(normalized)) {
            throw new BusinessConflictException("Chế độ tuyến GHN không hợp lệ.");
        }
        return normalized;
    }

    private String requireEvidence(String raw) {
        String safe = redactor.redact(raw);
        if (safe == null || safe.length() < 8) {
            throw new BusinessConflictException("Ghi chú kiểm chứng cần ít nhất 8 ký tự sau khi che thông tin nhạy cảm.");
        }
        return safe;
    }

    private Map<ShopAddressKind, ShopShippingAddressView> currentAddresses(UUID shopId) {
        Map<ShopAddressKind, ShopShippingAddressView> result = new EnumMap<>(ShopAddressKind.class);
        for (ShopAddressKind kind : ShopAddressKind.values()) {
            addressHeads.findByShopIdAndAddressKind(shopId, kind)
                    .flatMap(head -> addressRevisions.findByIdAndShopIdAndAddressKind(head.getRevisionId(), shopId, kind))
                    .map(row -> new ShopShippingAddressView(row.getId(), row.getAddressKind(), row.getRevision(),
                            row.getCatalogSource(), row.getProvinceCode(), row.getProvinceName(), row.getWardCode(),
                            row.getWardName(), row.getAddressLine(), row.getContactName(), row.getContactPhone(), row.getCreatedAt()))
                    .ifPresent(view -> result.put(kind, view));
        }
        return result;
    }

    private static void requireAdmin(UserPrincipal actor) {
        if (actor == null || actor.getId() == null
                || (actor.getRole() != com.utewedding.app.security.AppRole.ADMIN
                && actor.getRole() != com.utewedding.app.security.AppRole.SUPER_ADMIN)) {
            throw new org.springframework.security.access.AccessDeniedException("Chỉ quản trị viên được thay đổi cấu hình vận chuyển.");
        }
    }
}
