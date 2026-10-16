package com.utewedding.app.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.domain.entity.CartEntity;
import com.utewedding.app.domain.entity.CartItemEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.entity.ShippingCarrierEntity;
import com.utewedding.app.domain.entity.ShippingQuoteEntity;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ShippingQuoteExpiredException;
import com.utewedding.app.repository.CartItemRepository;
import com.utewedding.app.repository.CartRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ShippingCarrierRepository;
import com.utewedding.app.repository.ShippingQuoteRepository;
import com.utewedding.app.service.AdministrativeAddressService;
import com.utewedding.app.service.port.ShippingParcel;
import com.utewedding.app.service.port.ShippingProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShippingQuoteService {
    private static final Duration MAX_PROVIDER_QUOTE_LIFETIME = Duration.ofMinutes(10);

    private final CartRepository carts;
    private final CartItemRepository items;
    private final ShippingQuoteRepository quotes;
    private final ShippingProviderRegistry providers;
    private final ProductRepository products;
    private final ObjectMapper mapper;
    private final ShippingParcelFactory parcelFactory;
    private final AdministrativeAddressService addressDirectory;
    private final ShippingRoutingPolicy routingPolicy;
    private final ShippingCarrierRepository carriers;
    @Autowired(required = false)
    private MultiShopCheckoutSelectionResolver groupedSelections;

    /** Compatibility constructor used by older unit tests; production injects the carrier repository. */
    public ShippingQuoteService(CartRepository carts, CartItemRepository items, ShippingQuoteRepository quotes,
                                ShippingProviderRegistry providers, ObjectMapper mapper,
                                ProductRepository products, ShippingParcelFactory parcelFactory,
                                AdministrativeAddressService addressDirectory,
                                @Value("${shipping.internal-province-codes:79}") String internalProvinceCodes,
                                @Value("${shipping.internal-ghn-province-id:1000001}") String internalGhnProvinceId) {
        this(carts, items, quotes, providers, mapper, products, parcelFactory, addressDirectory,
                internalProvinceCodes, internalGhnProvinceId, null);
    }

    @Autowired
    public ShippingQuoteService(CartRepository carts, CartItemRepository items, ShippingQuoteRepository quotes,
                                ShippingProviderRegistry providers, ObjectMapper mapper,
                                ProductRepository products, ShippingParcelFactory parcelFactory,
                                AdministrativeAddressService addressDirectory,
                                @Value("${shipping.internal-province-codes:79}") String internalProvinceCodes,
                                @Value("${shipping.internal-ghn-province-id:1000001}") String internalGhnProvinceId,
                                ShippingCarrierRepository carriers) {
        this.carts = carts;
        this.items = items;
        this.quotes = quotes;
        this.providers = providers;
        this.mapper = mapper;
        this.products = products;
        this.parcelFactory = parcelFactory;
        this.addressDirectory = addressDirectory;
        this.carriers = carriers;
        this.routingPolicy = new ShippingRoutingPolicy(internalProvinceCodes, internalGhnProvinceId);
    }

    public QuoteResponse quote(UUID customerId, ShippingAddressRequest destination) {
        List<CartItemEntity> rows = cartRows(customerId);
        if (rows.stream().anyMatch(row -> !com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID
                .equals(currentProduct(row.getProductId()).getShopId()))) {
            throw new BusinessConflictException("Vui lòng chọn cửa hàng để lấy báo giá giao hàng.");
        }
        return quote(customerId, destination, rows, null);
    }

    /** Creates a platform quote bound to the exact selected cart row IDs for grouped checkout. */
    public QuoteResponse quoteGroupedPlatformSelection(UUID customerId, List<UUID> selectedCartItemIds,
            String selectionFingerprint, ShippingAddressRequest destination) {
        if (groupedSelections == null) {
            throw new BusinessConflictException("Không thể xác minh lựa chọn sản phẩm cho báo giá giao hàng.");
        }
        var resolved = groupedSelections.resolve(customerId, selectedCartItemIds, false);
        if (resolved.shops().size() != 1
                || !com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID.equals(resolved.shops().getFirst().shopId())
                || !resolved.shops().getFirst().fingerprint().equals(selectionFingerprint)) {
            throw new BusinessConflictException("Nhóm sản phẩm nền tảng đã thay đổi. Vui lòng tải lại giỏ hàng.");
        }
        List<CartItemEntity> rows = resolved.shops().getFirst().rows().stream()
                .map(MultiShopCheckoutSelectionResolver.SelectedRow::cartItem).toList();
        return quote(customerId, destination, rows, selectionFingerprint);
    }

    public QuoteResponse quotePlatformSelection(UUID customerId, String fingerprint,
                                                ShippingAddressRequest destination) {
        UUID platformShop = com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID;
        List<CartItemEntity> rows = cartRows(customerId).stream()
                .filter(row -> products.findById(row.getProductId())
                        .map(product -> platformShop.equals(product.getShopId())).orElse(false)).toList();
        List<com.utewedding.app.dto.response.CartItemResponse> snapshot = rows.stream().map(row -> {
            ProductEntity product = currentProduct(row.getProductId());
            return new com.utewedding.app.dto.response.CartItemResponse(row.getId(), row.getProductId(),
                    product.getName(), product.getSku(), product.getImageUrl(), product.getUnitPrice(),
                    row.getQuantity(), product.getDiscountPercent(), 0, 0, row.getCustomizationText());
        }).toList();
        if (rows.isEmpty() || fingerprint == null || !MessageDigest.isEqual(
                CheckoutSelectionResolver.fingerprint(platformShop, snapshot).getBytes(StandardCharsets.UTF_8),
                fingerprint.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessConflictException("Nhóm sản phẩm đã thay đổi. Vui lòng tải lại giỏ hàng.");
        }
        return quote(customerId, destination, rows, null);
    }

    private QuoteResponse quote(UUID customerId, ShippingAddressRequest destination, List<CartItemEntity> rows) {
        return quote(customerId, destination, rows, null);
    }

    private QuoteResponse quote(UUID customerId, ShippingAddressRequest destination, List<CartItemEntity> rows,
                                String selectionFingerprint) {
        String cartHash = selectionFingerprint == null ? cartHash(rows) : groupedCartHash(rows);
        AdministrativeAddressService.ResolvedAddress resolved = addressDirectory.resolve(
                destination.provinceCode(), destination.wardCode());
        String destinationHash = destinationHash(destination, resolved.catalogSource());
        ShippingQuoteEntity entity = new ShippingQuoteEntity();
        entity.setCustomerId(customerId);
        entity.setAddressCatalogSource(resolved.catalogSource());
        entity.setCartHash(cartHash);
        entity.setSelectedGroupFingerprint(selectionFingerprint);
        entity.setDestinationHash(destinationHash);
        applyCurrentPricing(entity, rows, resolved, destination);
        return response(quotes.save(entity));
    }

    private void applyCurrentPricing(ShippingQuoteEntity entity, List<CartItemEntity> rows,
                                     AdministrativeAddressService.ResolvedAddress resolved,
                                     ShippingAddressRequest destination) {
        CarrierRoute route = carrierRouteFor(resolved.catalogSource(), resolved.provinceCode());
        ShippingRoutingPolicy.Decision decision = routingPolicy.decide(
                resolved.catalogSource(), resolved.provinceCode());
        ShippingProvider.Quote result;
        ShippingParcel parcel = null;
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("source", decision.pricingMode().equals(ShippingRoutingPolicy.PRICING_FREE)
                ? "INTERNAL_FREE_POLICY" : "GHN_STAGING_PREVIEW");
        metadata.put("pricingMode", decision.pricingMode());
        metadata.put("policyVersion", decision.policyVersion());
        metadata.put("carrierId", route.carrierId().toString());
        metadata.put("carrierCode", route.carrierCode());
        metadata.put("carrierName", route.carrierName());
        if (ShippingRoutingPolicy.PRICING_FREE.equals(decision.pricingMode())) {
            result = new ShippingProvider.Quote(decision.methodCode(), decision.serviceCode(), 0L, "VND",
                    null, Map.copyOf(metadata), Instant.now().plus(MAX_PROVIDER_QUOTE_LIFETIME));
        } else if (ShippingRoutingPolicy.PRICING_PROVIDER_QUOTE.equals(decision.pricingMode())) {
            ShippingProvider provider = providers.require(decision.providerCode());
            if (!provider.capabilities().contains(ShippingProvider.Capability.QUOTE)) {
                throw new BusinessConflictException("GHN hiện không hỗ trợ báo giá trực tuyến.");
            }
            parcel = parcelFactory.fromCartRows(rows);
            long merchandiseAmount = rows.stream().map(row -> {
                ProductEntity product = currentProduct(row.getProductId());
                return Math.multiplyExact(product.getUnitPrice(), row.getQuantity());
            }).reduce(0L, Math::addExact);
            result = provider.quote(new ShippingProvider.QuoteRequest(merchandiseAmount, "VND",
                    destination, cartHash(rows), destinationHash(destination, resolved.catalogSource()),
                    parcel, 0L));
            validateProviderQuote(result);
            if (result.metadata() != null) metadata.putAll(result.metadata());
            metadata.put("source", "GHN_STAGING_PREVIEW");
            metadata.put("parcel", parcel);
            result = new ShippingProvider.Quote(result.methodCode(), result.serviceCode(), result.fee(),
                    result.currency(), result.providerReference(), Map.copyOf(metadata), result.expiresAt());
        } else {
            throw new BusinessConflictException("Chế độ tính phí giao hàng chưa được hỗ trợ.");
        }
        entity.setProviderCode(decision.providerCode());
        entity.setCarrierId(route.carrierId());
        entity.setCarrierCode(route.carrierCode());
        entity.setCarrierName(route.carrierName());
        entity.setCarrierAvailabilityVersion(route.availabilityVersion());
        entity.setPricingMode(decision.pricingMode());
        entity.setPolicyVersion(decision.policyVersion());
        entity.setMethodCode(result.methodCode());
        entity.setServiceCode(result.serviceCode());
        entity.setFee(result.fee());
        entity.setCurrency(result.currency());
        entity.setProviderQuoteRef(result.providerReference());
        entity.setMetadata(json(result.metadata()));
        entity.setExpiresAt(result.expiresAt());
    }

    private void validateProviderQuote(ShippingProvider.Quote result) {
        Instant now = Instant.now();
        if (result == null || result.fee() < 0 || !"VND".equalsIgnoreCase(result.currency())
                || result.methodCode() == null || result.methodCode().isBlank()
                || result.serviceCode() == null || result.serviceCode().isBlank()
                || result.expiresAt() == null || !result.expiresAt().isAfter(now)
                || Duration.between(now, result.expiresAt()).compareTo(MAX_PROVIDER_QUOTE_LIFETIME) > 0) {
            throw new BusinessConflictException("GHN chưa trả báo giá hợp lệ. Vui lòng thử lại sau.");
        }
    }

    @Transactional
    public ShippingQuoteEntity requireValid(UUID customerId, UUID quoteId, List<CartItemEntity> cartItems,
                                            ShippingAddressRequest destination) {
        ShippingQuoteEntity quote = requireMatching(customerId, quoteId, cartItems, destination, true, true);
        if (!quote.getExpiresAt().isAfter(Instant.now())) throw new ShippingQuoteExpiredException();
        requireActiveCarrier(quote, true);
        return quote;
    }

    /**
     * Validates a quote for a read-only preview. The preview is advisory; order
     * creation repeats this validation while holding the quote/carrier locks.
     */
    @Transactional(readOnly = true)
    public ShippingQuoteEntity requireValidForPreview(UUID customerId, UUID quoteId,
                                                      List<CartItemEntity> cartItems,
                                                      ShippingAddressRequest destination) {
        ShippingQuoteEntity quote = requireMatching(customerId, quoteId, cartItems, destination, false, true);
        if (!quote.getExpiresAt().isAfter(Instant.now())) throw new ShippingQuoteExpiredException();
        requireActiveCarrier(quote, false);
        return quote;
    }

    @Transactional(readOnly = true)
    public ShippingQuoteEntity requireGroupedValidForPreview(UUID customerId, UUID quoteId,
            List<CartItemEntity> selectedRows, ShippingAddressRequest destination, String selectionFingerprint) {
        ShippingQuoteEntity quote = requireMatching(customerId, quoteId, selectedRows, destination,
                false, true, selectionFingerprint);
        if (!quote.getExpiresAt().isAfter(Instant.now())) throw new ShippingQuoteExpiredException();
        requireActiveCarrier(quote, false);
        return quote;
    }

    @Transactional
    public ShippingQuoteEntity requireGroupedValidForCheckout(UUID customerId, UUID quoteId,
            List<CartItemEntity> selectedRows, ShippingAddressRequest destination, String selectionFingerprint) {
        ShippingQuoteEntity quote = requireMatching(customerId, quoteId, selectedRows, destination,
                true, true, selectionFingerprint);
        if (!quote.getExpiresAt().isAfter(Instant.now())) throw new ShippingQuoteExpiredException();
        requireActiveCarrier(quote, true);
        return quote;
    }

    @Transactional
    public void refreshExpired(UUID customerId, UUID quoteId, ShippingAddressRequest destination) {
        List<CartItemEntity> rows = cartRows(customerId);
        ShippingQuoteEntity quote = requireMatching(customerId, quoteId, rows, destination, true, false);
        AdministrativeAddressService.ResolvedAddress resolved = addressDirectory.resolve(
                destination.provinceCode(), destination.wardCode());
        if (quote.getExpiresAt().isAfter(Instant.now())) {
            try {
                if (isCurrentPolicyQuote(quote, resolved, true)) return;
            } catch (BusinessConflictException changedConfiguration) {
                // Rebuild from the current route below; the new quote must still fail closed if it is unavailable.
            }
        }
        // The quote ID remains stable for checkout idempotency; its carrier and price snapshot are refreshed.
        applyCurrentPricing(quote, rows, resolved, destination);
        quotes.save(quote);
    }

    private ShippingQuoteEntity requireMatching(UUID customerId, UUID quoteId, List<CartItemEntity> cartItems,
                                                ShippingAddressRequest destination, boolean lockForUpdate,
                                                boolean requireCurrentPolicy) {
        return requireMatching(customerId, quoteId, cartItems, destination, lockForUpdate,
                requireCurrentPolicy, null);
    }

    private ShippingQuoteEntity requireMatching(UUID customerId, UUID quoteId, List<CartItemEntity> cartItems,
                                                ShippingAddressRequest destination, boolean lockForUpdate,
                                                boolean requireCurrentPolicy, String selectionFingerprint) {
        if (quoteId == null) throw new BusinessConflictException("Vui lòng cập nhật phí giao hàng trước khi đặt đơn.");
        var savedQuote = lockForUpdate
                ? quotes.findByIdAndCustomerIdForUpdate(quoteId, customerId)
                : quotes.findByIdAndCustomerId(quoteId, customerId);
        ShippingQuoteEntity quote = savedQuote
                .orElseThrow(() -> new BusinessConflictException(
                        "Thông tin phí giao hàng không hợp lệ. Vui lòng cập nhật lại."));
        requirePlatformQuoteContext(quote, selectionFingerprint);
        AdministrativeAddressService.ResolvedAddress resolved = addressDirectory.resolve(
                destination.provinceCode(), destination.wardCode());
        String expectedCartHash = selectionFingerprint == null ? cartHash(cartItems) : groupedCartHash(cartItems);
        if (!resolved.catalogSource().equals(quote.getAddressCatalogSource())
                || !quote.getCartHash().equals(expectedCartHash)
                || !quote.getDestinationHash().equals(destinationHash(destination, resolved.catalogSource()))) {
            throw new BusinessConflictException(
                    "Giỏ hàng hoặc địa chỉ đã thay đổi. Vui lòng cập nhật lại phí giao hàng.");
        }
        if (requireCurrentPolicy && !isCurrentPolicyQuote(quote, resolved, lockForUpdate)) {
            throw new BusinessConflictException(
                    "Báo giá giao hàng đã cũ hoặc cấu hình vận chuyển đã thay đổi. Vui lòng cập nhật lại.");
        }
        return quote;
    }

    private void requirePlatformQuoteContext(ShippingQuoteEntity quote, String expectedSelectionFingerprint) {
        if (quote.getShopId() != null
                || !java.util.Objects.equals(quote.getSelectedGroupFingerprint(), expectedSelectionFingerprint)
                || quote.getPickupAddressRevisionId() != null || quote.getPickupAddressRevision() != null
                || quote.getShippingBindingId() != null || quote.getShippingBindingRevision() != null
                || quote.getShippingProviderAccountId() != null
                || quote.getShippingProviderAccountUpdatedAt() != null || quote.getShippingRouteId() != null) {
            throw new BusinessConflictException(
                    "Báo giá giao hàng không thuộc đơn hàng nền tảng. Vui lòng cập nhật lại phí giao hàng.");
        }
    }

    private List<CartItemEntity> cartRows(UUID customerId) {
        CartEntity cart = carts.findByUserId(customerId)
                .orElseThrow(() -> new BusinessConflictException("Cart is empty."));
        List<CartItemEntity> rows = items.findByCartIdOrderByIdAsc(cart.getId());
        if (rows.isEmpty()) throw new BusinessConflictException("Cart is empty.");
        return rows;
    }

    public String cartHash(List<CartItemEntity> rows) {
        return hash(rows.stream().sorted(Comparator.comparing(CartItemEntity::getProductId))
                .map(row -> {
                    ProductEntity product = currentProduct(row.getProductId());
                    return row.getProductId() + ":" + row.getQuantity() + ":" + product.getUnitPrice() + ":"
                            + product.getWeightGrams() + ":" + normal(product.getDimensions());
                }).reduce("", (a, b) -> a + "|" + b));
    }

    private String groupedCartHash(List<CartItemEntity> rows) {
        return hash(rows.stream().sorted(Comparator.comparing(CartItemEntity::getProductId)
                        .thenComparing(CartItemEntity::getId))
                .map(row -> {
                    ProductEntity product = currentProduct(row.getProductId());
                    return row.getId() + ":" + row.getProductId() + ":" + row.getQuantity() + ":"
                            + normal(row.getCustomizationText()) + ":" + product.getUnitPrice() + ":"
                            + product.getDiscountPercent() + ":" + product.getWeightGrams() + ":"
                            + normal(product.getDimensions()) + ":" + product.getVersion();
                }).reduce("", (a, b) -> a + "|" + b));
    }

    private ProductEntity currentProduct(UUID productId) {
        return products.findByIdAndActiveTrue(productId)
                .orElseThrow(() -> new BusinessConflictException("Sản phẩm trong giỏ không còn kinh doanh."));
    }

    public String destinationHash(ShippingAddressRequest destination) {
        return destinationHash(destination, addressDirectory.activeCatalogSource());
    }

    public String destinationHash(ShippingAddressRequest destination, String catalogSource) {
        if (destination == null) return hash("");
        return hash(normal(catalogSource) + "|" + normal(destination.recipientName()) + "|"
                + normal(destination.phone()) + "|" + normal(destination.addressLine()) + "|"
                + normal(destination.provinceCode()) + "|" + normal(destination.wardCode()));
    }

    public long fallbackFee(ShippingAddressRequest destination) {
        AdministrativeAddressService.ResolvedAddress resolved = addressDirectory.resolve(
                destination.provinceCode(), destination.wardCode());
        ShippingRoutingPolicy.Decision decision = routingPolicy.decide(
                resolved.catalogSource(), resolved.provinceCode());
        if (!ShippingRoutingPolicy.PRICING_FREE.equals(decision.pricingMode())) {
            throw new BusinessConflictException("Cần báo giá GHN mới trước khi tính phí giao hàng.");
        }
        return 0L;
    }

    public CarrierRoute carrierRouteFor(String catalogSource, String provinceCode) {
        return carrierRouteFor(catalogSource, provinceCode, false);
    }

    private CarrierRoute carrierRouteFor(String catalogSource, String provinceCode, boolean lockForUpdate) {
        if (carriers == null) {
            throw new BusinessConflictException("Danh mục nhà vận chuyển chưa được cấu hình.");
        }
        ShippingRoutingPolicy.Decision decision = routingPolicy.decide(catalogSource, provinceCode);
        ShippingCarrierEntity carrier = carriers.findActiveDefault(decision.coverageScope())
                .orElseThrow(() -> new BusinessConflictException(decision.coverageScope().equals("HCM")
                        ? "Chưa cấu hình đơn vị giao hàng nội bộ cho TP. Hồ Chí Minh. Vui lòng liên hệ quản trị viên."
                        : "Chưa cấu hình đơn vị giao hàng ngoại tỉnh. Vui lòng liên hệ quản trị viên."));
        if (lockForUpdate) {
            carrier = carriers.findByIdForUpdate(carrier.getId())
                    .orElseThrow(() -> new BusinessConflictException(
                            "Cấu hình giao hàng đã thay đổi. Vui lòng cập nhật báo giá."));
        }
        if (!carrier.isActive() || carrier.getDeletedAt() != null || !carrier.isDefaultForNewOrders()
                || !decision.providerCode().equalsIgnoreCase(carrier.getProviderCode())
                || !decision.pricingMode().equalsIgnoreCase(carrier.getPricingMode())) {
            throw new BusinessConflictException("Cấu hình giao hàng không khớp chính sách địa chỉ nhận hàng.");
        }
        return new CarrierRoute(carrier.getId(), carrier.getCode(), carrier.getDisplayName(),
                carrier.getProviderCode(), 0L, decision.serviceCode(), carrier.getAvailabilityVersion());
    }

    private void requireActiveCarrier(ShippingQuoteEntity quote, boolean lockForUpdate) {
        if (!isCarrierActive(quote, lockForUpdate)) {
            throw new BusinessConflictException(
                    "Đơn vị vận chuyển của báo giá đã ngừng hoạt động. Vui lòng cập nhật phí giao hàng.");
        }
    }

    private boolean isCarrierActive(ShippingQuoteEntity quote, boolean lockForUpdate) {
        if (carriers == null || quote.getCarrierId() == null) return false;
        ShippingCarrierEntity carrier = (lockForUpdate
                ? carriers.findByIdForUpdate(quote.getCarrierId())
                : carriers.findById(quote.getCarrierId())).orElse(null);
        return carrier != null && carrier.isActive() && carrier.getDeletedAt() == null
                && carrier.isDefaultForNewOrders()
                && carrier.getCode().equals(quote.getCarrierCode())
                && carrier.getProviderCode().equalsIgnoreCase(quote.getProviderCode())
                && carrier.getPricingMode().equalsIgnoreCase(quote.getPricingMode())
                && carrier.getAvailabilityVersion() == quote.getCarrierAvailabilityVersion();
    }

    /** Provider classification for delivery estimates; this method never supplies a customer fee. */
    public RoutingDecision routeFor(String catalogSource, String provinceCode) {
        ShippingRoutingPolicy.Decision decision = routingPolicy.decide(catalogSource, provinceCode);
        return new RoutingDecision(decision.providerCode(), 0L, decision.serviceCode());
    }

    private boolean isCurrentPolicyQuote(ShippingQuoteEntity quote,
                                        AdministrativeAddressService.ResolvedAddress resolved,
                                        boolean lockForUpdate) {
        ShippingRoutingPolicy.Decision decision = routingPolicy.decide(
                resolved.catalogSource(), resolved.provinceCode());
        if (quote.getPolicyVersion() != decision.policyVersion()
                || !decision.pricingMode().equalsIgnoreCase(quote.getPricingMode())
                || !decision.providerCode().equalsIgnoreCase(quote.getProviderCode())) {
            return false;
        }
        CarrierRoute current = carrierRouteFor(resolved.catalogSource(), resolved.provinceCode(), lockForUpdate);
        return current.carrierId().equals(quote.getCarrierId())
                && current.carrierCode().equals(quote.getCarrierCode())
                && current.availabilityVersion() == quote.getCarrierAvailabilityVersion()
                && isCarrierActive(quote, lockForUpdate);
    }

    private String normal(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private QuoteResponse response(ShippingQuoteEntity quote) {
        return new QuoteResponse(quote.getId(), quote.getProviderCode(), quote.getMethodCode(),
                quote.getServiceCode(), quote.getFee(), quote.getCurrency(), quote.getExpiresAt());
    }

    public record QuoteResponse(UUID id, String provider, String method, String service,
                                long fee, String currency, Instant expiresAt) { }
    public record RoutingDecision(String providerCode, long fee, String serviceCode) { }
    public record CarrierRoute(UUID carrierId, String carrierCode, String carrierName,
                               String providerCode, long customerFee, String serviceCode,
                               long availabilityVersion) { }
}
