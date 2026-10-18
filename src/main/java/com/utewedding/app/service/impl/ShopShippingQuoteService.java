package com.utewedding.app.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.domain.entity.ShippingQuoteEntity;
import com.utewedding.app.dto.request.ShopShippingQuoteRequest;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ShippingQuoteExpiredException;
import com.utewedding.app.repository.ShippingQuoteRepository;
import com.utewedding.app.service.port.ShopShippingQuoteProvider;
import com.utewedding.app.service.port.ShippingProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates and revalidates policy-routed quotes scoped to one vendor group. */
@Service
public class ShopShippingQuoteService {
    private static final Duration MAX_QUOTE_LIFETIME = Duration.ofMinutes(10);
    private static final Pattern SAFE_PROVIDER_REFERENCE = Pattern.compile("^[A-Za-z0-9._:-]{1,190}$");

    private final ShopShippingQuoteContextResolver contexts;
    private final ShopShippingQuoteProvider provider;
    private final ShippingQuoteRepository quotes;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    private jakarta.persistence.EntityManager entityManager;

    public ShopShippingQuoteService(ShopShippingQuoteContextResolver contexts,
                                   ShopShippingQuoteProvider provider,
                                   ShippingQuoteRepository quotes,
                                   ObjectMapper mapper) {
        this.contexts = contexts;
        this.provider = provider;
        this.quotes = quotes;
        this.mapper = mapper;
    }

    public ShippingQuoteService.QuoteResponse quote(UUID customerId, ShopShippingQuoteRequest request) {
        requireRequest(request);
        ShopShippingQuoteContextResolver.Context context = contexts.resolve(customerId,
                request.shopId(), request.selectionFingerprint(), request.destination());
        return persistQuote(customerId, context, request.selectionFingerprint(), request.destination(), false);
    }

    /** Creates a shop quote for a row-addressed grouped checkout selection. */
    public ShippingQuoteService.QuoteResponse quoteGroupedSelection(UUID customerId, UUID requestedShopId,
            java.util.List<UUID> selectedCartItemIds, com.utewedding.app.dto.request.ShippingAddressRequest destination) {
        ShopShippingQuoteContextResolver.Context context = contexts.resolveGrouped(customerId, requestedShopId,
                selectedCartItemIds, destination);
        return persistQuote(customerId, context, context.selectionFingerprint(), destination, true);
    }

    private ShippingQuoteService.QuoteResponse persistQuote(UUID customerId,
            ShopShippingQuoteContextResolver.Context context, String selectionFingerprint,
            com.utewedding.app.dto.request.ShippingAddressRequest destination, boolean grouped) {
        boolean free = ShippingRoutingPolicy.PRICING_FREE.equals(context.decision().pricingMode());
        ShippingProvider.Quote result = free ? freeQuote(context) : provider.quote(providerRequest(context));
        if (!free) validateProviderQuote(result);

        // Do not return a fee for a group/origin/route that changed while the provider was responding.
        ShopShippingQuoteContextResolver.Context current = grouped
                ? contexts.resolveGrouped(customerId, context.shopId(),
                        context.rows().stream().map(com.utewedding.app.domain.entity.CartItemEntity::getId).toList(),
                        destination)
                : contexts.resolve(customerId, context.shopId(), selectionFingerprint, destination);
        if (!context.contextKey().equals(current.contextKey())) {
            throw new BusinessConflictException(
                    "Giỏ hàng, địa chỉ lấy hàng hoặc tuyến giao hàng vừa thay đổi. Hãy lấy lại báo giá.");
        }

        ShippingQuoteEntity quote = new ShippingQuoteEntity();
        quote.setCustomerId(customerId);
        quote.setProviderCode(current.decision().providerCode());
        quote.setCarrierId(current.carrier().getId());
        quote.setCarrierCode(current.carrier().getCode());
        quote.setCarrierName(current.carrier().getDisplayName());
        quote.setCarrierAvailabilityVersion(current.carrier().getAvailabilityVersion());
        quote.setPricingMode(current.decision().pricingMode());
        quote.setPolicyVersion(current.decision().policyVersion());
        quote.setMethodCode(requiredText(result.methodCode(), "Nhà vận chuyển không trả loại dịch vụ hợp lệ."));
        quote.setServiceCode(requiredText(result.serviceCode(), "Nhà vận chuyển không trả mã dịch vụ hợp lệ."));
        quote.setFee(result.fee());
        quote.setCurrency("VND");
        quote.setCartHash(current.cartHash());
        quote.setDestinationHash(current.destinationHash());
        quote.setAddressCatalogSource(current.recipient().catalogSource());
        quote.setShopId(current.shopId());
        quote.setSelectedGroupFingerprint(current.selectionFingerprint());
        quote.setPickupAddressRevisionId(current.pickup().getId());
        quote.setPickupAddressRevision(current.pickup().getRevision());
        if (current.binding() != null) {
            quote.setShippingBindingId(current.binding().getId());
            quote.setShippingBindingRevision(current.binding().getRevision());
        }
        if (current.account() != null) {
            quote.setShippingProviderAccountId(current.account().getId());
            quote.setShippingProviderAccountUpdatedAt(current.account().getUpdatedAt());
        }
        quote.setShippingRouteId(current.route() == null ? null : current.route().getId());
        quote.setProviderQuoteRef(free ? null : safeProviderReference(result.providerReference()));
        quote.setMetadata(snapshotMetadata(current));
        quote.setExpiresAt(result.expiresAt());
        return response(quotes.save(quote));
    }

    /** VND-016 must call this while holding the cart/order creation locks before consuming the quote. */
    @Transactional(readOnly = true)
    public ShippingQuoteEntity requireValid(UUID customerId, UUID quoteId, UUID shopId,
                                            String selectionFingerprint,
                                            com.utewedding.app.dto.request.ShippingAddressRequest destination) {
        return requireValid(customerId, quoteId, shopId, selectionFingerprint, destination, false, null);
    }

    @Transactional
    public ShippingQuoteEntity requireValidForCheckout(UUID customerId, UUID quoteId, UUID shopId,
            String selectionFingerprint, com.utewedding.app.dto.request.ShippingAddressRequest destination) {
        return requireValid(customerId, quoteId, shopId, selectionFingerprint, destination, true, null);
    }

    @Transactional(readOnly = true)
    public ShippingQuoteEntity requireValidGroupedForPreview(UUID customerId, UUID quoteId, UUID shopId,
            java.util.List<UUID> selectedCartItemIds,
            com.utewedding.app.dto.request.ShippingAddressRequest destination) {
        ShopShippingQuoteContextResolver.Context context = contexts.resolveGrouped(customerId, shopId,
                selectedCartItemIds, destination);
        return requireValid(customerId, quoteId, shopId, context.selectionFingerprint(), destination, false,
                selectedCartItemIds);
    }

    @Transactional
    public ShippingQuoteEntity requireValidGroupedForCheckout(UUID customerId, UUID quoteId, UUID shopId,
            java.util.List<UUID> selectedCartItemIds,
            com.utewedding.app.dto.request.ShippingAddressRequest destination) {
        ShopShippingQuoteContextResolver.Context context = contexts.resolveGrouped(customerId, shopId,
                selectedCartItemIds, destination);
        return requireValid(customerId, quoteId, shopId, context.selectionFingerprint(), destination, true,
                selectedCartItemIds);
    }

    private ShippingQuoteEntity requireValid(UUID customerId, UUID quoteId, UUID shopId,
            String selectionFingerprint, com.utewedding.app.dto.request.ShippingAddressRequest destination,
            boolean lock, java.util.List<UUID> selectedCartItemIds) {
        if (quoteId == null) {
            throw new BusinessConflictException("Vui lòng cập nhật phí giao hàng cho cửa hàng đã chọn.");
        }
        ShippingQuoteEntity quote = (lock ? quotes.findByIdAndCustomerIdForUpdate(quoteId, customerId)
                : quotes.findByIdAndCustomerId(quoteId, customerId))
                .orElseThrow(() -> new BusinessConflictException("Báo giá giao hàng không hợp lệ. Hãy cập nhật lại."));
        if (!shopId.equals(quote.getShopId())
                || !selectionFingerprint.equals(quote.getSelectedGroupFingerprint())) {
            throw new BusinessConflictException("Báo giá thuộc cửa hàng hoặc nhóm sản phẩm khác.");
        }
        if (lock) {
            lockContext(com.utewedding.app.domain.entity.ShopShippingBindingEntity.class, quote.getShippingBindingId());
            lockContext(com.utewedding.app.domain.entity.ShippingProviderAccountEntity.class,
                    quote.getShippingProviderAccountId());
            lockContext(com.utewedding.app.domain.entity.ShopShippingRouteEntity.class, quote.getShippingRouteId());
            lockContext(com.utewedding.app.domain.entity.ShippingCarrierEntity.class, quote.getCarrierId());
        }
        ShopShippingQuoteContextResolver.Context current = selectedCartItemIds == null
                ? contexts.resolve(customerId, shopId, selectionFingerprint, destination)
                : contexts.resolveGrouped(customerId, shopId, selectedCartItemIds, destination);
        if (!quote.getCartHash().equals(current.cartHash())
                || !quote.getDestinationHash().equals(current.destinationHash())
                || !quote.getAddressCatalogSource().equals(current.recipient().catalogSource())
                || !quote.getPickupAddressRevisionId().equals(current.pickup().getId())
                || !quote.getPickupAddressRevision().equals(current.pickup().getRevision())
                || !java.util.Objects.equals(quote.getShippingBindingId(), current.binding() == null ? null : current.binding().getId())
                || !java.util.Objects.equals(quote.getShippingBindingRevision(), current.binding() == null ? null : current.binding().getRevision())
                || !java.util.Objects.equals(quote.getShippingProviderAccountId(), current.account() == null ? null : current.account().getId())
                || !java.util.Objects.equals(quote.getShippingProviderAccountUpdatedAt(),
                        current.account() == null ? null : current.account().getUpdatedAt())
                || !java.util.Objects.equals(quote.getShippingRouteId(), current.route() == null ? null : current.route().getId())
                || !quote.getCarrierId().equals(current.carrier().getId())
                || quote.getCarrierAvailabilityVersion() != current.carrier().getAvailabilityVersion()
                || !current.decision().pricingMode().equals(quote.getPricingMode())
                || current.decision().policyVersion() != quote.getPolicyVersion()
                || !current.decision().providerCode().equals(quote.getProviderCode())) {
            throw new BusinessConflictException(
                    "Giỏ hàng, địa chỉ lấy hàng hoặc tuyến giao hàng đã thay đổi. Hãy cập nhật báo giá.");
        }
        if (!quote.getExpiresAt().isAfter(Instant.now())) throw new ShippingQuoteExpiredException();
        return quote;
    }

    private <T> void lockContext(Class<T> type, UUID id) {
        if (id == null) return;
        T row = entityManager.find(type, id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (row == null) throw new BusinessConflictException("Cấu hình giao hàng đã thay đổi. Hãy cập nhật báo giá.");
        entityManager.refresh(row, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    private ShopShippingQuoteProvider.Request providerRequest(ShopShippingQuoteContextResolver.Context context) {
        var pickup = context.pickup();
        var returnAddress = context.returnAddress();
        return new ShopShippingQuoteProvider.Request(context.shopId(), context.binding().getId(),
                context.binding().getRevision(), context.account().getId(),
                context.account().getCredentialReference(), context.binding().getExternalShopId(),
                pickup.getCatalogSource(),
                new ShopShippingQuoteProvider.PickupAddress(pickup.getProvinceCode(), pickup.getProvinceName(),
                        pickup.getWardCode(), pickup.getWardName(), pickup.getAddressLine(),
                        pickup.getContactName(), pickup.getContactPhone()),
                new ShopShippingQuoteProvider.PickupAddress(returnAddress.getProvinceCode(),
                        returnAddress.getProvinceName(), returnAddress.getWardCode(), returnAddress.getWardName(),
                        returnAddress.getAddressLine(), returnAddress.getContactName(), returnAddress.getContactPhone()),
                new ShopShippingQuoteProvider.DestinationAddress(context.destination().recipientName(),
                        context.destination().phone(), context.destination().addressLine(),
                        context.recipient().provinceCode(), context.recipient().provinceName(),
                        context.recipient().wardCode(), context.recipient().wardName()),
                context.parcel());
    }

    private void validateProviderQuote(ShippingProvider.Quote result) {
        Instant now = Instant.now();
        if (result == null || result.fee() < 0 || !"VND".equalsIgnoreCase(result.currency())
                || result.methodCode() == null || result.methodCode().isBlank()
                || result.serviceCode() == null || result.serviceCode().isBlank()
                || result.expiresAt() == null || !result.expiresAt().isAfter(now)
                || Duration.between(now, result.expiresAt()).compareTo(MAX_QUOTE_LIFETIME) > 0) {
            throw new BusinessConflictException("GHN chưa trả báo giá hợp lệ. Vui lòng thử lại sau.");
        }
    }

    private String snapshotMetadata(ShopShippingQuoteContextResolver.Context context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", ShippingRoutingPolicy.PRICING_FREE.equals(context.decision().pricingMode())
                ? "INTERNAL_FREE_POLICY" : "SHOP_GHN_STAGING_PREVIEW");
        metadata.put("pricingMode", context.decision().pricingMode());
        metadata.put("policyVersion", context.decision().policyVersion());
        metadata.put("shopId", context.shopId());
        metadata.put("selectedGroupFingerprint", context.selectionFingerprint());
        metadata.put("pickupAddressRevisionId", context.pickup().getId());
        metadata.put("pickupAddressRevision", context.pickup().getRevision());
        if (context.returnAddress() != null) {
            metadata.put("returnAddressRevisionId", context.returnAddress().getId());
            metadata.put("returnAddressRevision", context.returnAddress().getRevision());
        }
        if (context.binding() != null) {
            metadata.put("shippingBindingId", context.binding().getId());
            metadata.put("shippingBindingRevision", context.binding().getRevision());
        }
        if (context.account() != null) metadata.put("shippingProviderAccountId", context.account().getId());
        if (context.route() != null) metadata.put("shippingRouteId", context.route().getId());
        metadata.put("parcel", context.parcel());
        try {
            return mapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể lưu bản chụp báo giá giao hàng.", exception);
        }
    }

    private ShippingProvider.Quote freeQuote(ShopShippingQuoteContextResolver.Context context) {
        return new ShippingProvider.Quote(context.decision().methodCode(), context.decision().serviceCode(),
                0L, "VND", null, Map.of("source", "INTERNAL_FREE_POLICY",
                        "pricingMode", context.decision().pricingMode(),
                        "policyVersion", context.decision().policyVersion()),
                Instant.now().plus(MAX_QUOTE_LIFETIME));
    }

    private String safeProviderReference(String value) {
        return value != null && SAFE_PROVIDER_REFERENCE.matcher(value).matches() ? value : null;
    }

    private String requiredText(String value, String error) {
        if (value == null || value.isBlank() || value.length() > 80) {
            throw new BusinessConflictException(error);
        }
        return value;
    }

    private void requireRequest(ShopShippingQuoteRequest request) {
        if (request == null || request.shopId() == null || request.selectionFingerprint() == null
                || request.destination() == null) {
            throw new BusinessConflictException("Thông tin báo giá giao hàng chưa đầy đủ.");
        }
    }

    private ShippingQuoteService.QuoteResponse response(ShippingQuoteEntity quote) {
        return new ShippingQuoteService.QuoteResponse(quote.getId(), quote.getProviderCode(),
                quote.getMethodCode(), quote.getServiceCode(), quote.getFee(), quote.getCurrency(),
                quote.getExpiresAt());
    }
}
