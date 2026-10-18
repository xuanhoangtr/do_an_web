package com.utewedding.app.service.impl;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.ShopAddressKind;
import com.utewedding.app.domain.ShopKind;
import com.utewedding.app.domain.ShopStatus;
import com.utewedding.app.domain.entity.CartEntity;
import com.utewedding.app.domain.entity.CartItemEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.entity.ShopAddressHeadEntity;
import com.utewedding.app.domain.entity.ShopAddressRevisionEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.domain.entity.ShopShippingBindingEntity;
import com.utewedding.app.domain.entity.ShopShippingRouteEntity;
import com.utewedding.app.domain.entity.ShippingCarrierEntity;
import com.utewedding.app.domain.entity.ShippingProviderAccountEntity;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.CartItemRepository;
import com.utewedding.app.repository.CartRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ShopAddressHeadRepository;
import com.utewedding.app.repository.ShopAddressRevisionRepository;
import com.utewedding.app.repository.ShopRepository;
import com.utewedding.app.repository.ShopShippingBindingRepository;
import com.utewedding.app.repository.ShopShippingRouteRepository;
import com.utewedding.app.repository.ShippingCarrierRepository;
import com.utewedding.app.repository.ShippingProviderAccountRepository;
import com.utewedding.app.service.AdministrativeAddressService;
import com.utewedding.app.service.CartService;
import com.utewedding.app.service.port.ShippingParcel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves only the authenticated customer's selected shop group and its current verified route. */
@Component
public class ShopShippingQuoteContextResolver {
    private final CartService carts;
    private final CheckoutSelectionResolver selections;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItems;
    private final ProductRepository products;
    private final ShopRepository shops;
    private final ShopAddressHeadRepository addressHeads;
    private final ShopAddressRevisionRepository addressRevisions;
    private final ShopShippingBindingRepository bindings;
    private final ShopShippingRouteRepository routes;
    private final ShippingProviderAccountRepository accounts;
    private final ShippingCarrierRepository carriers;
    private final AdministrativeAddressService addresses;
    private final ShippingParcelFactory parcels;
    private final MarketplaceFeatureFlags features;
    private final ShippingRoutingPolicy routingPolicy;
    private MultiShopCheckoutSelectionResolver groupedSelections;

    public ShopShippingQuoteContextResolver(CartService carts, CheckoutSelectionResolver selections,
            CartRepository cartRepository, CartItemRepository cartItems, ProductRepository products,
            ShopRepository shops, ShopAddressHeadRepository addressHeads,
            ShopAddressRevisionRepository addressRevisions, ShopShippingBindingRepository bindings,
            ShopShippingRouteRepository routes, ShippingProviderAccountRepository accounts,
            ShippingCarrierRepository carriers, AdministrativeAddressService addresses,
            ShippingParcelFactory parcels, MarketplaceFeatureFlags features) {
        this(carts, selections, cartRepository, cartItems, products, shops, addressHeads, addressRevisions,
                bindings, routes, accounts, carriers, addresses, parcels, features, "79", "1000001");
    }

    @Autowired
    public ShopShippingQuoteContextResolver(CartService carts, CheckoutSelectionResolver selections,
            CartRepository cartRepository, CartItemRepository cartItems, ProductRepository products,
            ShopRepository shops, ShopAddressHeadRepository addressHeads,
            ShopAddressRevisionRepository addressRevisions, ShopShippingBindingRepository bindings,
            ShopShippingRouteRepository routes, ShippingProviderAccountRepository accounts,
            ShippingCarrierRepository carriers, AdministrativeAddressService addresses,
            ShippingParcelFactory parcels, MarketplaceFeatureFlags features,
            @Value("${shipping.internal-province-codes:79}") String internalProvinceCodes,
            @Value("${shipping.internal-ghn-province-id:1000001}") String internalGhnProvinceId) {
        this.carts = carts;
        this.selections = selections;
        this.cartRepository = cartRepository;
        this.cartItems = cartItems;
        this.products = products;
        this.shops = shops;
        this.addressHeads = addressHeads;
        this.addressRevisions = addressRevisions;
        this.bindings = bindings;
        this.routes = routes;
        this.accounts = accounts;
        this.carriers = carriers;
        this.addresses = addresses;
        this.parcels = parcels;
        this.features = features;
        this.routingPolicy = new ShippingRoutingPolicy(internalProvinceCodes, internalGhnProvinceId);
    }

    @Autowired(required = false)
    void attachGroupedSelections(MultiShopCheckoutSelectionResolver resolver) {
        this.groupedSelections = resolver;
    }

    public Context resolve(UUID customerId, UUID shopId, String selectionFingerprint,
                           ShippingAddressRequest destination) {
        requireFeatureEnabled();
        if (customerId == null || shopId == null || destination == null) {
            throw new BusinessConflictException("Thông tin cửa hàng hoặc địa chỉ giao hàng chưa đầy đủ.");
        }
        CheckoutSelectionResolver.Selection selection = selections.resolve(
                carts.getCart(customerId), shopId, selectionFingerprint);
        List<CartItemEntity> rows = selectedRows(customerId, selection);
        return resolveResolved(customerId, shopId, selectionFingerprint, destination, selection, rows);
    }

    /** Resolves a vendor quote against only the authenticated customer's submitted cart rows. */
    public Context resolveGrouped(UUID customerId, UUID shopId, List<UUID> selectedCartItemIds,
                                  ShippingAddressRequest destination) {
        requireGroupedFeatureEnabled();
        if (customerId == null || shopId == null || destination == null || groupedSelections == null) {
            throw new BusinessConflictException("Thông tin cửa hàng hoặc địa chỉ giao hàng chưa đầy đủ.");
        }
        MultiShopCheckoutSelectionResolver.Selection resolved = groupedSelections.resolve(
                customerId, selectedCartItemIds, false);
        MultiShopCheckoutSelectionResolver.ShopSelection shopSelection = resolved.shops().stream()
                .filter(group -> shopId.equals(group.shopId()))
                .findFirst()
                .orElseThrow(() -> new BusinessConflictException(
                        "Cửa hàng đã chọn không sở hữu sản phẩm nào trong các dòng giỏ hàng."));
        List<CartItemEntity> rows = shopSelection.rows().stream()
                .map(MultiShopCheckoutSelectionResolver.SelectedRow::cartItem).toList();
        List<com.utewedding.app.dto.response.CartItemResponse> items = shopSelection.rows().stream()
                .map(selected -> {
                    CartItemEntity row = selected.cartItem();
                    ProductEntity product = selected.product();
                    return new com.utewedding.app.dto.response.CartItemResponse(row.getId(), product.getId(),
                            product.getName(), product.getSku(), product.getImageUrl(), product.getUnitPrice(),
                            row.getQuantity(), product.getDiscountPercent(), 0, 0,
                            row.getCustomizationText(), shopSelection.shopId(), shopSelection.shopName(), true, null);
                }).toList();
        CheckoutSelectionResolver.Selection checkoutSelection = new CheckoutSelectionResolver.Selection(
                shopSelection.shopId(), shopSelection.shopName(), items, 0, 0, 0, shopSelection.fingerprint());
        return resolveResolved(customerId, shopSelection.shopId(), shopSelection.fingerprint(),
                destination, checkoutSelection, rows);
    }

    private Context resolveResolved(UUID customerId, UUID shopId, String selectionFingerprint,
                                    ShippingAddressRequest destination,
                                    CheckoutSelectionResolver.Selection selection,
                                    List<CartItemEntity> rows) {
        ShopEntity shop = shops.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cửa hàng."));
        if (shop.getKind() != ShopKind.VENDOR || shop.getStatus() != ShopStatus.ACTIVE) {
            throw new BusinessConflictException("Cửa hàng này chưa thể nhận đơn hàng.");
        }
        String groupCartHash = groupCartHash(shopId, rows);

        String activeCatalog = addresses.activeCatalogSource();
        AdministrativeAddressService.ResolvedAddress recipient = addresses.resolve(
                destination.provinceCode(), destination.wardCode());
        if (!activeCatalog.equals(recipient.catalogSource())) {
            throw new BusinessConflictException("Danh mục địa chỉ vừa thay đổi. Chọn lại địa chỉ giao hàng.");
        }
        String destinationHash = destinationHash(destination, recipient);

        ShopAddressHeadEntity pickupHead = addressHeads.findByShopIdAndAddressKind(shopId, ShopAddressKind.PICKUP)
                .orElseThrow(() -> new BusinessConflictException("Cửa hàng chưa khai báo địa chỉ lấy hàng."));
        ShopAddressRevisionEntity pickup = addressRevisions.findByIdAndShopIdAndAddressKind(
                        pickupHead.getRevisionId(), shopId, ShopAddressKind.PICKUP)
                .orElseThrow(() -> new BusinessConflictException("Địa chỉ lấy hàng hiện tại không hợp lệ."));
        if (!activeCatalog.equals(pickup.getCatalogSource())) {
            throw new BusinessConflictException("Danh mục địa chỉ lấy hàng đã cũ. Cửa hàng cần cập nhật địa chỉ.");
        }
        AdministrativeAddressService.ResolvedAddress canonicalPickup = addresses.resolve(
                pickup.getCatalogSource(), pickup.getProvinceCode(), pickup.getWardCode());
        if (!pickup.getProvinceCode().equals(canonicalPickup.provinceCode())
                || !pickup.getWardCode().equals(canonicalPickup.wardCode())
                || !sameName(pickup.getProvinceName(), canonicalPickup.provinceName())
                || !sameName(pickup.getWardName(), canonicalPickup.wardName())) {
            throw new BusinessConflictException("Địa chỉ lấy hàng không còn khớp danh mục hiện tại.");
        }

        ShippingRoutingPolicy.Decision decision = routingPolicy.decide(
                recipient.catalogSource(), recipient.provinceCode());
        ShopShippingBindingEntity binding = null;
        ShopAddressRevisionEntity returnAddress = null;
        ShippingProviderAccountEntity account = null;
        ShopShippingRouteEntity route = null;
        if (ShippingRoutingPolicy.PRICING_PROVIDER_QUOTE.equals(decision.pricingMode())) {
            List<ShopShippingBindingEntity> activeBindings = bindings.findByShopIdOrderByRevisionDesc(shopId).stream()
                    .filter(ShopShippingBindingEntity::isActive)
                    .filter(value -> "VERIFIED".equals(value.getStatus()))
                    .toList();
            if (activeBindings.size() != 1) {
                throw new BusinessConflictException(activeBindings.isEmpty()
                        ? "Cửa hàng chưa có cấu hình GHN đã xác minh và đang hoạt động."
                        : "Cửa hàng có nhiều cấu hình GHN đang hoạt động; cần quản trị viên kiểm tra.");
            }
            binding = activeBindings.getFirst();
            if (!pickup.getId().equals(binding.getPickupAddressRevisionId())) {
                throw new BusinessConflictException("Địa chỉ lấy hàng đã đổi. Cửa hàng cần xác minh lại cấu hình GHN.");
            }
            String routeMode = binding.getRouteMode();
            boolean legacyRoutes = "LEGACY_ROUTES".equals(routeMode);
            boolean apiQuote = "API_QUOTE".equals(routeMode);
            if (!legacyRoutes && !apiQuote) {
                throw new BusinessConflictException("Chế độ tuyến GHN của cửa hàng không hợp lệ.");
            }
            if (legacyRoutes && !binding.isRouteCoverageVerified()) {
                throw new BusinessConflictException("Cửa hàng chưa có cấu hình GHN đã xác minh và đang hoạt động.");
            }
            account = accounts.findByIdAndProviderCode(binding.getProviderAccountId(), "GHN")
                    .orElseThrow(() -> new BusinessConflictException("Không tìm thấy tài khoản GHN của cửa hàng."));
            if (!"STAGING".equals(account.getEnvironment()) || !"VERIFIED".equals(account.getStatus())
                    || account.getCredentialReference() == null || account.getCredentialReference().isBlank()) {
                throw new BusinessConflictException("Tài khoản GHN Staging của cửa hàng chưa được xác minh.");
            }
            returnAddress = addressRevisions.findByIdAndShopIdAndAddressKind(
                            binding.getReturnAddressRevisionId(), shopId, ShopAddressKind.RETURN)
                    .orElseThrow(() -> new BusinessConflictException("Địa chỉ hoàn hàng hiện tại không hợp lệ."));
            if (!activeCatalog.equals(returnAddress.getCatalogSource())) {
                throw new BusinessConflictException("Danh mục địa chỉ hoàn hàng đã cũ. Cửa hàng cần cập nhật địa chỉ.");
            }
            AdministrativeAddressService.ResolvedAddress canonicalReturn = addresses.resolve(
                    returnAddress.getCatalogSource(), returnAddress.getProvinceCode(), returnAddress.getWardCode());
            if (!returnAddress.getProvinceCode().equals(canonicalReturn.provinceCode())
                    || !returnAddress.getWardCode().equals(canonicalReturn.wardCode())
                    || !sameName(returnAddress.getProvinceName(), canonicalReturn.provinceName())
                    || !sameName(returnAddress.getWardName(), canonicalReturn.wardName())) {
                throw new BusinessConflictException("Địa chỉ hoàn hàng không còn khớp danh mục hiện tại.");
            }

            if (legacyRoutes) {
                route = routes.findByBindingIdAndShopIdAndCatalogSourceAndProvinceCodeAndWardCodeAndStatusAndActiveTrue(
                                binding.getId(), shopId, recipient.catalogSource(), recipient.provinceCode(),
                                recipient.wardCode(), "VERIFIED")
                        .orElseThrow(() -> new BusinessConflictException(
                                "Cửa hàng chưa có tuyến GHN đã xác minh tới phường/xã này."));
                if (route.getVerifiedAt() == null) {
                    throw new BusinessConflictException("Tuyến GHN của cửa hàng chưa được xác minh đầy đủ.");
                }
                AdministrativeAddressService.ResolvedAddress canonicalRoute = addresses.resolve(
                        route.getCatalogSource(), route.getProvinceCode(), route.getWardCode());
                if (!recipient.catalogSource().equals(canonicalRoute.catalogSource())
                        || !recipient.provinceCode().equals(canonicalRoute.provinceCode())
                        || !recipient.wardCode().equals(canonicalRoute.wardCode())) {
                    throw new BusinessConflictException("Tuyến GHN không khớp địa chỉ giao hàng đã chọn.");
                }
            }
        }

        ShippingCarrierEntity carrier = carriers.findActiveDefault(decision.coverageScope())
                .filter(value -> decision.providerCode().equalsIgnoreCase(value.getProviderCode()))
                .filter(value -> decision.pricingMode().equalsIgnoreCase(value.getPricingMode()))
                .orElseThrow(() -> new BusinessConflictException(
                        "Đơn vị giao hàng chưa được cấu hình theo chính sách địa chỉ nhận hàng."));
        ShippingParcel parcel = parcels.fromCartRows(rows);
        return new Context(shopId, selectionFingerprint, selection, rows, groupCartHash, destination,
                recipient, destinationHash, pickup, returnAddress, binding, account, route, carrier, parcel, decision);
    }

    public void requireFeatureEnabled() {
        if (features == null || !features.isEnabled(Feature.VENDOR_CHECKOUT)
                || !features.isEnabled(Feature.MULTI_SHOP_CART)) {
            throw new ResourceNotFoundException("Tính năng thanh toán theo cửa hàng hiện chưa được bật.");
        }
    }

    public void requireGroupedFeatureEnabled() {
        if (features == null || !features.isEnabled(Feature.GROUPED_CHECKOUT)) {
            throw new ResourceNotFoundException("Tính năng thanh toán theo nhóm hiện chưa được bật.");
        }
    }

    private List<CartItemEntity> selectedRows(UUID customerId, CheckoutSelectionResolver.Selection selection) {
        CartEntity cart = cartRepository.findByUserId(customerId)
                .orElseThrow(() -> new BusinessConflictException("Giỏ hàng đã thay đổi. Vui lòng tải lại."));
        List<CartItemEntity> allRows = cartItems.findByCartIdOrderByIdAsc(cart.getId());
        var itemIds = selection.items().stream().map(item -> item.id()).collect(java.util.stream.Collectors.toSet());
        List<CartItemEntity> selected = allRows.stream().filter(row -> itemIds.contains(row.getId())).toList();
        if (selected.size() != itemIds.size() || selected.isEmpty()) {
            throw new BusinessConflictException("Nhóm sản phẩm đã thay đổi. Vui lòng tải lại giỏ hàng.");
        }
        for (CartItemEntity row : selected) {
            ProductEntity product = products.findByIdAndActiveTrue(row.getProductId())
                    .orElseThrow(() -> new BusinessConflictException("Sản phẩm trong giỏ không còn kinh doanh."));
            if (!selection.shopId().equals(product.getShopId())) {
                throw new BusinessConflictException("Cửa hàng của sản phẩm đã thay đổi. Vui lòng tải lại giỏ hàng.");
            }
        }
        return selected;
    }

    private String groupCartHash(UUID shopId, List<CartItemEntity> rows) {
        List<CartItemEntity> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(CartItemEntity::getProductId)
                .thenComparing(CartItemEntity::getId));
        StringBuilder canonical = new StringBuilder();
        append(canonical, shopId.toString());
        for (CartItemEntity row : ordered) {
            ProductEntity product = products.findByIdAndActiveTrue(row.getProductId()).orElseThrow();
            append(canonical, row.getId().toString());
            append(canonical, row.getProductId().toString());
            append(canonical, Integer.toString(row.getQuantity()));
            append(canonical, row.getCustomizationText());
            append(canonical, Long.toString(product.getUnitPrice()));
            append(canonical, Integer.toString(product.getDiscountPercent()));
            append(canonical, product.getName());
            append(canonical, product.getSku());
            append(canonical, Integer.toString(product.getWeightGrams() == null ? 0 : product.getWeightGrams()));
            append(canonical, product.getDimensions());
        }
        return sha256(canonical.toString());
    }

    private String destinationHash(ShippingAddressRequest request,
                                   AdministrativeAddressService.ResolvedAddress recipient) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, recipient.catalogSource());
        append(canonical, request.recipientName());
        append(canonical, request.phone());
        append(canonical, request.addressLine());
        append(canonical, recipient.provinceCode());
        append(canonical, recipient.wardCode());
        return sha256(canonical.toString());
    }

    private boolean sameName(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(':').append(safe).append(';');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    public record Context(UUID shopId, String selectionFingerprint, CheckoutSelectionResolver.Selection selection,
                          List<CartItemEntity> rows, String cartHash, ShippingAddressRequest destination,
                          AdministrativeAddressService.ResolvedAddress recipient, String destinationHash,
                          ShopAddressRevisionEntity pickup, ShopAddressRevisionEntity returnAddress,
                          ShopShippingBindingEntity binding,
                          ShippingProviderAccountEntity account, ShopShippingRouteEntity route,
                          ShippingCarrierEntity carrier, ShippingParcel parcel,
                          ShippingRoutingPolicy.Decision decision) {
        public Context {
            rows = List.copyOf(rows);
        }

        public String contextKey() {
            return shopId + "|" + selectionFingerprint + "|" + cartHash + "|" + destinationHash + "|"
                    + pickup.getId() + "|" + pickup.getRevision() + "|" + revisionKey(returnAddress) + "|"
                    + decision.providerCode() + "|" + decision.pricingMode() + "|" + decision.policyVersion() + "|"
                    + bindingKey(binding) + "|" + accountKey(account) + "|" + routeKey(route) + "|"
                    + carrier.getId() + "|" + carrier.getCode() + "|" + carrier.getProviderCode() + "|"
                    + carrier.getPricingMode() + "|" + carrier.isActive() + "|" + carrier.getAvailabilityVersion();
        }

        private static String revisionKey(ShopAddressRevisionEntity revision) {
            return revision == null ? "" : revision.getId() + ":" + revision.getRevision();
        }

        private static String bindingKey(ShopShippingBindingEntity binding) {
            return binding == null ? "" : binding.getId() + ":" + binding.getRevision() + ":"
                    + binding.getExternalShopId() + ":" + binding.getRouteMode();
        }

        private static String accountKey(ShippingProviderAccountEntity account) {
            return account == null ? "" : account.getId() + ":" + account.getEnvironment() + ":"
                    + account.getStatus() + ":" + account.getUpdatedAt() + ":"
                    + referenceDigest(account.getCredentialReference());
        }

        private static String routeKey(ShopShippingRouteEntity route) {
            return route == null ? "" : route.getId() + ":" + route.getCatalogSource() + ":"
                    + route.getProvinceCode() + ":" + route.getWardCode() + ":" + route.getStatus() + ":"
                    + route.isActive() + ":" + route.getVerifiedAt();
        }
    }

    private static String referenceDigest(String value) {
        if (value == null) return "";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}
