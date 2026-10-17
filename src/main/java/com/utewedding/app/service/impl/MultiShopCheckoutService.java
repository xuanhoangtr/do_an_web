package com.utewedding.app.service.impl;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.CheckoutGroupFulfillmentStatus;
import com.utewedding.app.domain.CheckoutGroupLifecycleStatus;
import com.utewedding.app.domain.CheckoutGroupPaymentStatus;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.OverallFulfillmentStatus;
import com.utewedding.app.domain.PaymentMethod;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.ShopDefaults;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.VoucherUsageSlot;
import com.utewedding.app.domain.entity.CartItemEntity;
import com.utewedding.app.domain.entity.CheckoutGroupEntity;
import com.utewedding.app.domain.entity.OrderAppliedDiscountEntity;
import com.utewedding.app.domain.entity.OrderAppliedDiscountItemAllocationEntity;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderFulfillmentEntity;
import com.utewedding.app.domain.entity.OrderItemEntity;
import com.utewedding.app.domain.entity.PaymentEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.entity.ShipInfoEntity;
import com.utewedding.app.domain.entity.ShippingQuoteEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.dto.request.MultiShopCheckoutRequest;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import com.utewedding.app.dto.response.MultiShopCheckoutPreviewResponse;
import com.utewedding.app.dto.response.MultiShopCheckoutResponse;
import com.utewedding.app.dto.response.ShippingAddressResponse;
import com.utewedding.app.event.OrderCreatedEvent;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.observability.CommerceMetrics;
import com.utewedding.app.repository.CartItemRepository;
import com.utewedding.app.repository.CartRepository;
import com.utewedding.app.repository.CheckoutGroupRepository;
import com.utewedding.app.repository.OrderAppliedDiscountItemAllocationRepository;
import com.utewedding.app.repository.OrderAppliedDiscountRepository;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.PaymentRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ShipInfoRepository;
import com.utewedding.app.repository.ShopRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.AdministrativeAddressService;
import com.utewedding.app.service.OrderIntegrityProbe;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.OrderShippingSnapshotService;
import com.utewedding.app.service.payment.GroupedPaymentIntegrityService;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.port.DiscountPolicy;
import com.utewedding.app.util.ProductOptions;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Selection, quote, pricing and atomic order-group orchestration for MCO-04/05. */
@Service
public class MultiShopCheckoutService {
    private static final int CHECKOUT_POLICY_VERSION = 1;
    private static final int MAX_LINE_QUANTITY = 99;
    private static final DateTimeFormatter NUMBER_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final CheckoutGroupRepository groups;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final PaymentRepository payments;
    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final ProductRepository products;
    private final ShopRepository shops;
    private final UserRepository users;
    private final ShipInfoRepository shipInfos;
    private final OrderFulfillmentRepository fulfillments;
    private final OrderAppliedDiscountRepository appliedDiscounts;
    private final OrderAppliedDiscountItemAllocationRepository appliedDiscountAllocations;
    private final MultiShopCheckoutSelectionResolver selections;
    private final ShippingQuoteService platformQuotes;
    private final ShopShippingQuoteService shopQuotes;
    private final DiscountPolicy discounts;
    private final AuditLogPort audit;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final AdministrativeAddressService addresses;
    private final MarketplaceFeatureFlags features;
    private final OrderShippingSnapshotService shippingSnapshots;
    private final com.utewedding.app.service.impl.AccountingDocumentService accountingDocuments;
    private final com.utewedding.app.service.impl.CodReconciliationService codReconciliation;
    private final com.utewedding.app.service.impl.ShopFinancialService shopFinancial;
    private final GroupedPaymentIntegrityService groupedPayments;
    private CommerceMetrics metrics = CommerceMetrics.noop();
    private OrderIntegrityProbe integrityProbe;
    @Value("${orders.payment.vnpay-unpaid-ttl:24h}")
    private Duration vnpayUnpaidTtl = Duration.ofHours(24);

    @Autowired
    public MultiShopCheckoutService(CheckoutGroupRepository groups, OrderRepository orders,
            OrderItemRepository orderItems, PaymentRepository payments,
            CartRepository carts, CartItemRepository cartItems, ProductRepository products,
            ShopRepository shops, UserRepository users, ShipInfoRepository shipInfos,
            OrderFulfillmentRepository fulfillments,
            OrderAppliedDiscountRepository appliedDiscounts,
            OrderAppliedDiscountItemAllocationRepository appliedDiscountAllocations,
            MultiShopCheckoutSelectionResolver selections, ShippingQuoteService platformQuotes,
            ShopShippingQuoteService shopQuotes,
            @Qualifier("voucherDiscountPolicy") DiscountPolicy discounts, AuditLogPort audit,
            EntityManager entityManager, ApplicationEventPublisher events,
            AdministrativeAddressService addresses, MarketplaceFeatureFlags features,
            OrderShippingSnapshotService shippingSnapshots,
            com.utewedding.app.service.impl.AccountingDocumentService accountingDocuments,
            com.utewedding.app.service.impl.CodReconciliationService codReconciliation,
            com.utewedding.app.service.impl.ShopFinancialService shopFinancial,
            GroupedPaymentIntegrityService groupedPayments) {
        this.groups = groups;
        this.orders = orders;
        this.orderItems = orderItems;
        this.payments = payments;
        this.carts = carts;
        this.cartItems = cartItems;
        this.products = products;
        this.shops = shops;
        this.users = users;
        this.shipInfos = shipInfos;
        this.fulfillments = fulfillments;
        this.appliedDiscounts = appliedDiscounts;
        this.appliedDiscountAllocations = appliedDiscountAllocations;
        this.selections = selections;
        this.platformQuotes = platformQuotes;
        this.shopQuotes = shopQuotes;
        this.discounts = discounts;
        this.audit = audit;
        this.entityManager = entityManager;
        this.events = events;
        this.addresses = addresses;
        this.features = features;
        this.shippingSnapshots = shippingSnapshots;
        this.accountingDocuments = accountingDocuments;
        this.codReconciliation = codReconciliation;
        this.shopFinancial = shopFinancial;
        this.groupedPayments = groupedPayments;
    }

    @Autowired(required = false)
    void attachMetrics(CommerceMetrics value) { metrics = value == null ? CommerceMetrics.noop() : value; }

    @Autowired(required = false)
    void attachIntegrityProbe(OrderIntegrityProbe value) { integrityProbe = value; }

    /** Preview is deliberately outside a DB transaction while provider quote calls run. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MultiShopCheckoutPreviewResponse preview(UUID customerId, MultiShopCheckoutRequest request) {
        requireGroupedCheckout(request);
        AddressSnapshot destination = resolveDestination(customerId, request);
        MultiShopCheckoutSelectionResolver.PreviewResolution resolved = selections.resolveForPreview(
                customerId, request.selectedCartItemIds());
        MultiShopCheckoutSelectionResolver.Selection selection = resolved.selection();
        Map<UUID, MultiShopCheckoutRequest.ShopOptions> options = requireShopOptions(selection, request.shops());
        List<PreviewShop> results = new ArrayList<>();
        for (MultiShopCheckoutSelectionResolver.ShopSelection selectedShop : selection.shops()) {
            MultiShopCheckoutRequest.ShopOptions shopOptions = options.get(selectedShop.shopId());
            BasePricing base = basePricing(selectedShop);
            QuoteSnapshot quote = null;
            ShopPricing priced = null;
            String error = resolved.errors().containsKey(selectedShop.shopId())
                    ? safeError(resolved.errors().get(selectedShop.shopId())) : null;
            if (error == null) {
                try {
                    quote = resolveQuote(customerId, selectedShop, shopOptions, destination.shipping(), false);
                    priced = priceShop(customerId, selectedShop, shopOptions, base, quote, false);
                } catch (BusinessConflictException conflict) {
                    error = safeError(conflict.getMessage());
                }
            }
            results.add(new PreviewShop(selectedShop, shopOptions, base, quote, priced, error));
        }
        Map<UUID, String> batchVoucherErrors = batchVoucherUsageErrors(results.stream()
                .map(PreviewShop::pricing).filter(Objects::nonNull).toList(), customerId, false);
        if (!batchVoucherErrors.isEmpty()) {
            results = results.stream().map(result -> {
                String batchError = batchVoucherErrors.get(result.selection().shopId());
                if (batchError == null || result.pricing() == null) return result;
                return new PreviewShop(result.selection(), result.options(), result.base(), result.quote(), null,
                        joinErrors(result.error(), batchError));
            }).toList();
        }
        boolean ready = results.stream().allMatch(result -> result.pricing() != null);
        Instant expiresAt = results.stream().map(PreviewShop::quote).filter(Objects::nonNull)
                .map(QuoteSnapshot::expiresAt).min(Comparator.naturalOrder()).orElse(Instant.now());
        List<String> errors = results.stream().filter(result -> result.pricing() == null)
                .map(result -> result.selection().shopName() + ": " + result.error()).toList();
        MultiShopCheckoutPreviewResponse.GroupTotals totals = ready ? groupTotals(
                results.stream().map(PreviewShop::pricing).toList()) : null;
        String revision = previewRevision(selection, request, destination, results);
        List<MultiShopCheckoutPreviewResponse.ShopPreview> shopResponses = results.stream()
                .map(this::toPreview).toList();
        return new MultiShopCheckoutPreviewResponse(revision, expiresAt, destination.response(),
                request.paymentMethod(), shopResponses, totals, ready, errors);
    }

    /** Creates a group and every child order in the same transaction, consuming only selected cart rows. */
    @Transactional(rollbackFor = Exception.class)
    public MultiShopCheckoutResponse place(UUID customerId, MultiShopCheckoutRequest request,
                                           RequestContext context) {
        requireGroupedCheckout(request);
        String key = requiredIdempotencyKey(request.idempotencyKey());
        if (request.previewRevision() == null || request.previewRevision().isBlank()
                || request.expectedTotalAmount() == null || request.expectedTotalAmount() < 0) {
            throw new BusinessConflictException("Xác nhận lại bản xem trước và tổng tiền trước khi đặt hàng.");
        }
        // Validate the shared destination before claiming the key; re-resolve it after the cart lock.
        resolveDestination(customerId, request);
        String requestHash = MultiShopCheckoutRequestHasher.hash(customerId, request);
        String selectionClaimRevision = idsRevision(request.selectedCartItemIds());
        PaymentMethod method = request.paymentMethod();
        Instant groupExpiry = method == PaymentMethod.VNPAY ? Instant.now().plus(vnpayUnpaidTtl) : null;
        int claimed = groups.claimIfAbsent(customerId, key, requestHash, generateGroupNumber(),
                method.name(), "VND", groupExpiry, CHECKOUT_POLICY_VERSION, selectionClaimRevision);
        if (claimed == 0) return replay(customerId, key, requestHash);

        CheckoutGroupEntity group = groups.findByCustomerIdAndIdempotencyKeyForUpdate(customerId, key)
                .orElseThrow(() -> new BusinessConflictException("Không thể giữ yêu cầu checkout. Vui lòng thử lại."));
        if (!customerId.equals(group.getCustomerId()) || !Objects.equals(group.getRequestHash(), requestHash)) {
            throw new BusinessConflictException("Khoá idempotency đã được dùng cho một yêu cầu khác.");
        }
        if (integrityProbe != null) integrityProbe.beforeIdempotencyClaim();
        MultiShopCheckoutSelectionResolver.Selection selection = selections.resolve(
                customerId, request.selectedCartItemIds(), true);
        Map<UUID, MultiShopCheckoutRequest.ShopOptions> options = requireShopOptions(selection, request.shops());
        AddressSnapshot destination = resolveDestination(customerId, request);

        // Reserve voucher definition locks globally by code, then validate quotes in quote-ID order.
        discounts.lockForCheckout(options.values().stream()
                .flatMap(value -> java.util.stream.Stream.of(value.discountCode(), value.shippingDiscountCode()))
                .filter(value -> value != null && !value.isBlank()).toList());
        Map<UUID, QuoteSnapshot> quoteSnapshots = new LinkedHashMap<>();
        List<MultiShopCheckoutSelectionResolver.ShopSelection> quoteOrder = selection.shops().stream()
                .sorted(Comparator.comparing(shop -> options.get(shop.shopId()).shippingQuoteId(),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        for (MultiShopCheckoutSelectionResolver.ShopSelection selectedShop : quoteOrder) {
            quoteSnapshots.put(selectedShop.shopId(), resolveQuote(customerId, selectedShop,
                    options.get(selectedShop.shopId()), destination.shipping(), true));
        }
        List<ShopPricing> pricing = selection.shops().stream().map(selectedShop -> priceShop(customerId,
                selectedShop, options.get(selectedShop.shopId()), basePricing(selectedShop),
                quoteSnapshots.get(selectedShop.shopId()), true)).toList();

        Map<UUID, String> batchVoucherErrors = batchVoucherUsageErrors(pricing, customerId, true);
        if (!batchVoucherErrors.isEmpty()) {
            List<String> messages = pricing.stream()
                    .filter(value -> batchVoucherErrors.containsKey(value.selection().shopId()))
                    .map(value -> value.selection().shopName() + ": "
                            + batchVoucherErrors.get(value.selection().shopId()))
                    .toList();
            throw new BusinessConflictException(String.join("; ", messages));
        }
        if (pricing.stream().anyMatch(value -> !value.ready())) {
            throw new BusinessConflictException("Một cửa hàng chưa thể hoàn tất checkout. Hãy cập nhật lại thông tin.");
        }
        MultiShopCheckoutPreviewResponse.GroupTotals totals = groupTotals(pricing);
        String currentRevision = previewRevision(selection, request, destination,
                pricing.stream().map(value -> new PreviewShop(value.selection(), value.options(),
                        value.base(), value.quote(), value, null)).toList());
        if (!constantTimeEquals(currentRevision, request.previewRevision())) {
            throw new BusinessConflictException("Bản xem trước đã thay đổi. Hãy xem lại checkout trước khi đặt.");
        }
        if (totals.totalAmount() == null || !Objects.equals(totals.totalAmount(), request.expectedTotalAmount())) {
            throw new BusinessConflictException("Tổng tiền đã thay đổi. Hãy xem lại checkout trước khi đặt.");
        }

        reserveInventory(pricing);
        if (integrityProbe != null) integrityProbe.afterInventoryReserved();
        group.setPaymentMethod(method.name());
        group.setCurrency("VND");
        group.setSubtotal(totals.subtotal());
        group.setProductDiscountAmount(totals.productDiscountAmount());
        group.setVoucherDiscountAmount(totals.voucherDiscountAmount());
        group.setShippingFeeBeforeDiscount(totals.shippingFeeBeforeDiscount());
        group.setShippingDiscountAmount(totals.shippingDiscountAmount());
        group.setShippingFee(totals.shippingFee());
        group.setTotalAmount(totals.totalAmount());
        group.setPaymentStatus(CheckoutGroupPaymentStatus.PENDING);
        group.setLifecycleStatus(CheckoutGroupLifecycleStatus.PENDING);
        group.setFulfillmentStatus(CheckoutGroupFulfillmentStatus.PENDING);
        group.setExpiresAt(groupExpiry);
        group.setPolicyVersion(CHECKOUT_POLICY_VERSION);
        group.setSelectionRevision(selection.revision());
        group.setShipInfoId(destination.shipInfoId());
        fillGroupAddress(group, destination);
        groups.save(group);
        entityManager.flush();

        List<PlacedChild> placed = new ArrayList<>();
        List<VoucherReservation> voucherReservations = new ArrayList<>();
        for (ShopPricing shopPricing : pricing) {
            OrderEntity order = createChildOrder(customerId, group, shopPricing, destination, method, groupExpiry);
            placed.add(new PlacedChild(order, shopPricing.selection().shopId()));
            collectVoucherReservations(voucherReservations, order, shopPricing);
        }
        voucherReservations.stream().sorted(Comparator.comparing(VoucherReservation::code,
                        String.CASE_INSENSITIVE_ORDER).thenComparing(value -> value.order().getShopId())
                        .thenComparing(value -> value.order().getId()))
                .forEach(value -> discounts.reserve(value.code(), customerId, value.order().getId(),
                        value.amount(), value.order().getShopId()));

        List<UUID> selectedRowIds = selection.rowIds();
        if (cartItems.consumeSelectedRows(selection.cartId(), selectedRowIds) != selectedRowIds.size()) {
            throw new BusinessConflictException("Một số dòng giỏ hàng đã thay đổi. Không có đơn hàng nào được tạo.");
        }
        entityManager.flush();
        if (totals.totalAmount() == 0) {
            groupedPayments.finalizeZeroDueGroup(customerId, group.getId(), context);
        }
        audit.record("CHECKOUT_GROUP_CREATED", customerId, "CheckoutGroup", group.getId(),
                Map.of("totalAmount", group.getTotalAmount(), "orderCount", placed.size(),
                        "paymentMethod", method.name()), context);
        for (PlacedChild child : placed) {
            OrderEntity order = child.order();
            audit.record("ORDER_CREATED", customerId, "Order", order.getId(),
                    Map.of("totalAmount", order.getTotalAmount(), "checkoutGroupId", group.getId().toString()), context);
            events.publishEvent(new OrderCreatedEvent(customerId, order.getOrderNumber(),
                    order.getTotalAmount(), order.getCurrency()));
            metrics.orderCreated(method.name());
        }
        return toResponse(group, placed.stream().map(PlacedChild::order).toList());
    }

    /** Group reads stay available after the storefront flag is turned off. */
    @Transactional(readOnly = true)
    public MultiShopCheckoutResponse get(UUID actorId, UUID groupId) {
        CheckoutGroupEntity group = groups.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhóm checkout."));
        boolean owner = actorId != null && actorId.equals(group.getCustomerId());
        boolean administrator = actorId != null && users.findById(actorId)
                .map(user -> user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.SUPER_ADMIN)
                .orElse(false);
        if (!owner && !administrator) throw new AccessDeniedException("Bạn không có quyền xem nhóm checkout này.");
        return toResponse(group, orders.findByCheckoutGroupIdOrderByCreatedAtAsc(groupId));
    }

    private void requireGroupedCheckout(MultiShopCheckoutRequest request) {
        if (features == null || !features.isEnabled(Feature.GROUPED_CHECKOUT)) {
            throw new ResourceNotFoundException("Tính năng thanh toán nhiều cửa hàng hiện chưa được bật.");
        }
        if (request == null || request.paymentMethod() == null) {
            throw new BusinessConflictException("Chọn phương thức thanh toán trước khi tiếp tục.");
        }
        if (request.paymentMethod() != PaymentMethod.COD && request.paymentMethod() != PaymentMethod.VNPAY) {
            throw new BusinessConflictException("Checkout theo nhóm hiện hỗ trợ COD hoặc VNPAY.");
        }
    }

    private AddressSnapshot resolveDestination(UUID customerId, MultiShopCheckoutRequest request) {
        boolean hasId = request.shippingAddressId() != null;
        boolean hasInline = request.shipping() != null;
        if (hasId == hasInline) {
            throw new BusinessConflictException("Chọn một địa chỉ đã lưu hoặc nhập một địa chỉ giao hàng.");
        }
        ShipInfoEntity saved = null;
        ShippingAddressRequest shipping = request.shipping();
        if (hasId) {
            saved = shipInfos.findByIdAndUserId(request.shippingAddressId(), customerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy địa chỉ giao hàng."));
            if (!Objects.equals(saved.getAddressCatalogSource(), addresses.activeCatalogSource())
                    || saved.getProvinceCode() == null || saved.getWardCode() == null) {
                throw new BusinessConflictException("Địa chỉ đã lưu cần được cập nhật theo danh mục địa chỉ hiện tại.");
            }
            shipping = new ShippingAddressRequest(saved.getRecipientName(), saved.getPhone(),
                    saved.getAddressLine(), saved.getProvinceCode(), saved.getWardCode());
        }
        if (shipping == null) throw new BusinessConflictException("Thông tin giao hàng là bắt buộc.");
        AdministrativeAddressService.ResolvedAddress resolved = addresses.resolve(
                shipping.provinceCode(), shipping.wardCode());
        if (!Objects.equals(resolved.catalogSource(), addresses.activeCatalogSource())) {
            throw new BusinessConflictException("Danh mục địa chỉ vừa thay đổi. Hãy chọn lại địa chỉ giao hàng.");
        }
        ShippingAddressResponse response = new ShippingAddressResponse(saved == null ? null : saved.getId(),
                saved == null ? null : saved.getLabel(), shipping.recipientName(), shipping.phone(),
                shipping.addressLine(), resolved.wardName(), null, resolved.provinceName(),
                resolved.provinceCode(), resolved.provinceName(), resolved.wardCode(), resolved.wardName(),
                saved != null && saved.isDefaultAddress(), resolved.catalogSource());
        return new AddressSnapshot(shipping, saved == null ? null : saved.getId(), resolved, response);
    }

    private Map<UUID, MultiShopCheckoutRequest.ShopOptions> requireShopOptions(
            MultiShopCheckoutSelectionResolver.Selection selection,
            List<MultiShopCheckoutRequest.ShopOptions> options) {
        if (options == null || options.isEmpty()) {
            throw new BusinessConflictException("Cần chọn thông tin checkout cho từng cửa hàng.");
        }
        Map<UUID, MultiShopCheckoutRequest.ShopOptions> indexed = new LinkedHashMap<>();
        for (MultiShopCheckoutRequest.ShopOptions option : options) {
            if (option == null || option.shopId() == null || indexed.putIfAbsent(option.shopId(), option) != null) {
                throw new BusinessConflictException("Thông tin checkout có cửa hàng bị thiếu hoặc lặp.");
            }
        }
        List<UUID> derived = selection.shops().stream().map(MultiShopCheckoutSelectionResolver.ShopSelection::shopId)
                .sorted().toList();
        if (!derived.equals(indexed.keySet().stream().sorted().toList())) {
            throw new BusinessConflictException("Danh sách cửa hàng không khớp các dòng sản phẩm đã chọn.");
        }
        return Map.copyOf(indexed);
    }

    private QuoteSnapshot resolveQuote(UUID customerId,
            MultiShopCheckoutSelectionResolver.ShopSelection shop,
            MultiShopCheckoutRequest.ShopOptions options, ShippingAddressRequest destination,
            boolean lockForCheckout) {
        UUID quoteId = options.shippingQuoteId();
        if (lockForCheckout && quoteId == null) {
            throw new BusinessConflictException("Cần lấy lại báo giá giao hàng cho " + shop.shopName() + ".");
        }
        if (ShopDefaults.PLATFORM_SHOP_ID.equals(shop.shopId())) {
            ShippingQuoteEntity quote;
            if (quoteId == null) {
                ShippingQuoteService.QuoteResponse created = platformQuotes.quoteGroupedPlatformSelection(
                        customerId, shop.rowIds(), shop.fingerprint(), destination);
                return new QuoteSnapshot(created.id(), created.provider(), created.fee(), created.expiresAt());
            }
            quote = lockForCheckout
                    ? platformQuotes.requireGroupedValidForCheckout(customerId, quoteId, rows(shop), destination,
                            shop.fingerprint())
                    : platformQuotes.requireGroupedValidForPreview(customerId, quoteId, rows(shop), destination,
                            shop.fingerprint());
            return new QuoteSnapshot(quote.getId(), quote.getProviderCode(), quote.getFee(), quote.getExpiresAt());
        }
        ShippingQuoteEntity quote;
        if (quoteId == null) {
            ShippingQuoteService.QuoteResponse created = shopQuotes.quoteGroupedSelection(
                    customerId, shop.shopId(), shop.rowIds(), destination);
            return new QuoteSnapshot(created.id(), created.provider(), created.fee(), created.expiresAt());
        }
        quote = lockForCheckout
                ? shopQuotes.requireValidGroupedForCheckout(customerId, quoteId, shop.shopId(), shop.rowIds(), destination)
                : shopQuotes.requireValidGroupedForPreview(customerId, quoteId, shop.shopId(), shop.rowIds(), destination);
        return new QuoteSnapshot(quote.getId(), quote.getProviderCode(), quote.getFee(), quote.getExpiresAt());
    }

    private List<CartItemEntity> rows(MultiShopCheckoutSelectionResolver.ShopSelection shop) {
        return shop.rows().stream().map(MultiShopCheckoutSelectionResolver.SelectedRow::cartItem).toList();
    }

    private BasePricing basePricing(MultiShopCheckoutSelectionResolver.ShopSelection selection) {
        long subtotal = 0;
        long productDiscount = 0;
        List<MultiShopCheckoutPreviewResponse.ItemLine> lines = new ArrayList<>();
        List<OrderItemEntity> items = new ArrayList<>();
        try {
            for (MultiShopCheckoutSelectionResolver.SelectedRow selected : selection.rows()) {
                CartItemEntity row = selected.cartItem();
                ProductEntity product = selected.product();
                long gross = Math.multiplyExact(product.getUnitPrice(), row.getQuantity());
                long lineDiscount = Math.multiplyExact(gross, product.getDiscountPercent()) / 100;
                long lineTotal = Math.subtractExact(gross, lineDiscount);
                subtotal = Math.addExact(subtotal, gross);
                productDiscount = Math.addExact(productDiscount, lineDiscount);
                String customization = ProductOptions.resolvePhotoSize(
                        product.getCustomizationGuide(), row.getCustomizationText());
                lines.add(new MultiShopCheckoutPreviewResponse.ItemLine(row.getId(), product.getId(),
                        orderItemName(product.getName(), customization), product.getSku(), product.getImageUrl(),
                        customization, product.getUnitPrice(), row.getQuantity(), product.getDiscountPercent(),
                        lineDiscount, lineTotal));
                OrderItemEntity item = new OrderItemEntity();
                item.setProductId(product.getId());
                item.setShopId(selection.shopId());
                item.setProductName(orderItemName(product.getName(), customization));
                item.setUnitPrice(product.getUnitPrice());
                item.setQuantity(row.getQuantity());
                item.setDiscountAmount(lineDiscount);
                item.setLineTotal(lineTotal);
                items.add(item);
            }
        } catch (ArithmeticException overflow) {
            throw new BusinessConflictException("Giá trị đơn hàng vượt giới hạn được hỗ trợ.");
        }
        return new BasePricing(subtotal, productDiscount, List.copyOf(lines), List.copyOf(items));
    }

    private ShopPricing priceShop(UUID customerId,
            MultiShopCheckoutSelectionResolver.ShopSelection selection,
            MultiShopCheckoutRequest.ShopOptions options, BasePricing base,
            QuoteSnapshot quote, boolean lockVouchers) {
        try {
            long eligible = Math.subtractExact(base.subtotal(), base.productDiscountAmount());
            DiscountPolicy.CheckoutDiscounts checkoutDiscounts = calculateDiscounts(options, eligible,
                    quote.fee(), quote.providerCode(), customerId, selection.shopId(), lockVouchers);
            long merchandiseDiscount = Math.max(0, Math.min(eligible, checkoutDiscounts.merchandise().amount()));
            long shippingDiscount = Math.max(0, Math.min(quote.fee(), checkoutDiscounts.shipping().amount()));
            long shippingPayable = Math.subtractExact(quote.fee(), shippingDiscount);
            long total = Math.addExact(Math.subtractExact(eligible, merchandiseDiscount), shippingPayable);
            allocateVoucher(base.items(), merchandiseDiscount);
            return new ShopPricing(selection, options, base, quote, checkoutDiscounts,
                    merchandiseDiscount, shippingDiscount, shippingPayable, total);
        } catch (ArithmeticException overflow) {
            throw new BusinessConflictException("Giá trị đơn hàng hoặc voucher vượt giới hạn được hỗ trợ.");
        }
    }

    private Map<UUID, String> batchVoucherUsageErrors(Collection<ShopPricing> pricing, UUID customerId,
                                                       boolean lock) {
        Map<String, Integer> requestedUses = new LinkedHashMap<>();
        Map<String, List<UUID>> shopsByCode = new LinkedHashMap<>();
        for (ShopPricing shop : pricing) {
            for (DiscountPolicy.DiscountResult result : List.of(
                    shop.discounts().merchandise(), shop.discounts().shipping())) {
                String appliedCode = result == null ? null : result.appliedCode();
                if (appliedCode == null || appliedCode.isBlank()) continue;
                String normalized = code(appliedCode);
                requestedUses.merge(normalized, 1, Math::addExact);
                List<UUID> shopIds = shopsByCode.computeIfAbsent(normalized, ignored -> new ArrayList<>());
                if (!shopIds.contains(shop.selection().shopId())) shopIds.add(shop.selection().shopId());
            }
        }
        if (requestedUses.isEmpty()) return Map.of();
        Map<String, String> codeErrors = discounts.validateBatchUsage(customerId, requestedUses, lock);
        if (codeErrors == null || codeErrors.isEmpty()) return Map.of();
        Map<UUID, String> errorsByShop = new LinkedHashMap<>();
        codeErrors.forEach((rawCode, message) -> {
            String normalized = code(rawCode);
            for (UUID shopId : shopsByCode.getOrDefault(normalized, List.of())) {
                errorsByShop.merge(shopId, message, MultiShopCheckoutService::joinErrors);
            }
        });
        return Map.copyOf(errorsByShop);
    }

    private static String joinErrors(String prior, String next) {
        if (prior == null || prior.isBlank()) return next;
        if (next == null || next.isBlank() || prior.contains(next)) return prior;
        return prior + "; " + next;
    }

    private DiscountPolicy.CheckoutDiscounts calculateDiscounts(
            MultiShopCheckoutRequest.ShopOptions options, long eligible, long shippingFee,
            String providerCode, UUID customerId, UUID shopId, boolean lock) {
        String merchandiseCode = options.discountCode();
        String shippingCode = options.shippingDiscountCode();
        if (discounts instanceof VoucherDiscountPolicy || (shippingCode != null && !shippingCode.isBlank())) {
            return lock
                    ? discounts.calculateCheckoutSlots(merchandiseCode, shippingCode, eligible, shippingFee,
                            providerCode, customerId, shopId)
                    : discounts.previewCheckoutSlots(merchandiseCode, shippingCode, eligible, shippingFee,
                            providerCode, customerId, shopId);
        }
        DiscountPolicy.DiscountResult legacy = lock
                ? discounts.calculate(merchandiseCode, eligible)
                : discounts.previewForUser(merchandiseCode, eligible, customerId);
        if (legacy == null) legacy = DiscountPolicy.DiscountResult.none();
        if (legacy.type() != null && legacy.type().isShippingVoucher()) {
            return new DiscountPolicy.CheckoutDiscounts(DiscountPolicy.DiscountResult.none(), legacy);
        }
        return new DiscountPolicy.CheckoutDiscounts(legacy, DiscountPolicy.DiscountResult.none());
    }

    private void reserveInventory(List<ShopPricing> pricing) {
        Map<UUID, ProductReservation> reservations = new LinkedHashMap<>();
        for (ShopPricing shop : pricing) {
            for (MultiShopCheckoutSelectionResolver.SelectedRow selected : shop.selection().rows()) {
                UUID productId = selected.product().getId();
                ProductReservation prior = reservations.get(productId);
                int quantity = prior == null ? selected.cartItem().getQuantity()
                        : Math.addExact(prior.quantity(), selected.cartItem().getQuantity());
                reservations.put(productId, new ProductReservation(selected.product(), quantity));
            }
        }
        for (ProductReservation reservation : reservations.values().stream()
                .sorted(Comparator.comparing(value -> value.product().getId())).toList()) {
            ProductEntity product = reservation.product();
            int remaining = product.getStockQuantity() - reservation.quantity();
            long version = product.getVersion();
            entityManager.detach(product);
            if (products.decrementStockIfAvailable(product.getId(), reservation.quantity(), version) != 1) {
                throw new BusinessConflictException("Tồn kho đã thay đổi. Hãy xem lại checkout trước khi đặt.");
            }
            product.setStockQuantity(remaining);
        }
    }

    private OrderEntity createChildOrder(UUID customerId, CheckoutGroupEntity group, ShopPricing pricing,
            AddressSnapshot destination, PaymentMethod method, Instant groupExpiry) {
        Instant now = Instant.now();
        OrderEntity order = new OrderEntity(customerId, now);
        order.setShopId(pricing.selection().shopId());
        order.setShopNameSnapshot(pricing.selection().shopName());
        order.setCheckoutGroupId(group.getId());
        order.setShipInfoId(destination.shipInfoId());
        order.setOrderNumber(generateOrderNumber());
        order.setIdempotencyKey(childIdempotencyKey(group.getId(), pricing.selection().shopId()));
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        // The group owns unpaid expiry. Leaving the child expiry empty keeps the legacy
        // order-level expiration job from closing one child independently.
        order.setExpiresAt(null);
        order.setSubtotal(pricing.base().subtotal());
        order.setDiscountAmount(Math.addExact(pricing.base().productDiscountAmount(),
                pricing.merchandiseDiscountAmount()));
        order.setShippingFee(pricing.shippingFee());
        order.setShippingFeeBeforeDiscount(pricing.quote().fee());
        order.setShippingDiscountAmount(pricing.shippingDiscountAmount());
        order.setVoucherDiscountAmount(pricing.merchandiseDiscountAmount());
        order.setVoucherType(pricing.discounts().legacyType());
        order.setTotalAmount(pricing.totalAmount());
        order.setCurrency("VND");
        order.setDiscountCode(pricing.discounts().legacyCode());
        order.setPaymentMethod(method.name());
        order.setCustomerNote(trimNote(pricing.options().customerNote()));
        fillOrderAddress(order, destination);
        order = orders.save(order);
        entityManager.flush();

        if (group.getTotalAmount() > 0) {
            PaymentEntity pending = new PaymentEntity();
            pending.setOrderId(order.getId());
            pending.setStatus(PaymentStatus.PENDING);
            pending.setAmount(order.getTotalAmount());
            pending.setCurrency(order.getCurrency());
            pending.setProvider(method.name());
            payments.save(pending);
        }

        for (OrderItemEntity item : pricing.base().items()) item.setOrderId(order.getId());
        orderItems.saveAll(pricing.base().items());
        entityManager.flush();
        shippingSnapshots.create(order, pricing.quote().id(), destination.shipping());
        accountingDocuments.ensure(order,
                com.utewedding.app.domain.AccountingDocumentType.ORDER_CONFIRMATION, null);
        if (method == PaymentMethod.COD && group.getTotalAmount() > 0) codReconciliation.initialize(order.getId());
        persistAppliedDiscountSnapshots(order, pricing.base().items(), pricing.discounts());
        if (!ShopDefaults.PLATFORM_SHOP_ID.equals(order.getShopId())) {
            shopFinancial.snapshotAtCheckout(order, pricing.base().items(), pricing.discounts().merchandise());
        }
        return order;
    }

    private void collectVoucherReservations(List<VoucherReservation> target, OrderEntity order,
                                           ShopPricing pricing) {
        for (DiscountPolicy.DiscountResult result : List.of(
                pricing.discounts().merchandise(), pricing.discounts().shipping())) {
            if (result != null && result.appliedCode() != null) {
                long amount = result.type() != null && result.type().isShippingVoucher()
                        ? order.getShippingDiscountAmount() : order.getVoucherDiscountAmount();
                target.add(new VoucherReservation(order, result.appliedCode(), amount));
            }
        }
    }

    private void persistAppliedDiscountSnapshots(OrderEntity order, List<OrderItemEntity> items,
                                                 DiscountPolicy.CheckoutDiscounts checkoutDiscounts) {
        for (DiscountPolicy.DiscountResult result : List.of(checkoutDiscounts.merchandise(), checkoutDiscounts.shipping())) {
            if (result == null || result.appliedCode() == null || result.voucherId() == null) continue;
            VoucherUsageSlot slot = result.type() != null && result.type().isShippingVoucher()
                    ? VoucherUsageSlot.SHIPPING : VoucherUsageSlot.MERCHANDISE;
            long amount = slot == VoucherUsageSlot.SHIPPING
                    ? order.getShippingDiscountAmount() : order.getVoucherDiscountAmount();
            OrderAppliedDiscountEntity snapshot = appliedDiscounts.saveAndFlush(OrderAppliedDiscountEntity.liveUsage(
                    order.getId(), order.getShopId(), result.voucherId(), slot, result.appliedCode(), result.type(),
                    result.scope(), result.fundingSource(), amount));
            if (slot != VoucherUsageSlot.MERCHANDISE) continue;
            List<OrderAppliedDiscountItemAllocationEntity> allocations = items.stream()
                    .map(item -> OrderAppliedDiscountItemAllocationEntity.of(snapshot.getId(), order.getId(),
                            order.getShopId(), item.getId(), item.getVoucherDiscountAllocated()))
                    .toList();
            appliedDiscountAllocations.saveAll(allocations);
            appliedDiscountAllocations.flush();
        }
    }

    private void allocateVoucher(List<OrderItemEntity> items, long voucherDiscount) {
        long total = items.stream().mapToLong(OrderItemEntity::getLineTotal).reduce(0L, Math::addExact);
        if (items.isEmpty()) return;
        if (voucherDiscount == 0 || total == 0) {
            items.forEach(item -> { item.setVoucherDiscountAllocated(0); item.setNetPaidAmount(item.getLineTotal()); });
            return;
        }
        record Remainder(OrderItemEntity item, long value) { }
        List<Remainder> remainders = new ArrayList<>();
        long assigned = 0;
        for (OrderItemEntity item : items) {
            long product = Math.multiplyExact(voucherDiscount, item.getLineTotal());
            long share = product / total;
            assigned = Math.addExact(assigned, share);
            item.setVoucherDiscountAllocated(share);
            remainders.add(new Remainder(item, product % total));
        }
        remainders.sort(Comparator.comparingLong(Remainder::value).reversed()
                .thenComparing(value -> value.item().getProductId()));
        for (long i = 0; i < voucherDiscount - assigned; i++) {
            OrderItemEntity item = remainders.get((int) (i % remainders.size())).item();
            item.setVoucherDiscountAllocated(item.getVoucherDiscountAllocated() + 1);
        }
        items.forEach(item -> item.setNetPaidAmount(item.getLineTotal() - item.getVoucherDiscountAllocated()));
    }

    private void fillOrderAddress(OrderEntity order, AddressSnapshot destination) {
        ShippingAddressRequest shipping = destination.shipping();
        AdministrativeAddressService.ResolvedAddress resolved = destination.resolved();
        order.setShippingRecipientName(required(shipping.recipientName(), "Tên người nhận là bắt buộc."));
        order.setShippingPhone(required(shipping.phone(), "Số điện thoại là bắt buộc."));
        order.setShippingAddress(required(shipping.addressLine(), "Địa chỉ giao hàng là bắt buộc."));
        order.setShippingWard(resolved.wardName());
        order.setShippingProvince(resolved.provinceName());
        order.setShippingProvinceCode(resolved.provinceCode());
        order.setShippingProvinceName(resolved.provinceName());
        order.setShippingWardCode(resolved.wardCode());
        order.setShippingWardName(resolved.wardName());
        order.setShippingAddressCatalogSource(resolved.catalogSource());
    }

    private void fillGroupAddress(CheckoutGroupEntity group, AddressSnapshot destination) {
        ShippingAddressRequest shipping = destination.shipping();
        AdministrativeAddressService.ResolvedAddress resolved = destination.resolved();
        group.setShippingRecipientName(required(shipping.recipientName(), "Tên người nhận là bắt buộc."));
        group.setShippingPhone(required(shipping.phone(), "Số điện thoại là bắt buộc."));
        group.setShippingAddress(required(shipping.addressLine(), "Địa chỉ giao hàng là bắt buộc."));
        group.setShippingWard(resolved.wardName());
        group.setShippingProvince(resolved.provinceName());
        group.setShippingProvinceCode(resolved.provinceCode());
        group.setShippingProvinceName(resolved.provinceName());
        group.setShippingWardCode(resolved.wardCode());
        group.setShippingWardName(resolved.wardName());
        group.setShippingAddressCatalogSource(resolved.catalogSource());
    }

    private MultiShopCheckoutPreviewResponse.ShopPreview toPreview(PreviewShop value) {
        ShopPricing pricing = value.pricing();
        QuoteSnapshot quote = value.quote();
        return new MultiShopCheckoutPreviewResponse.ShopPreview(value.selection().shopId(),
                value.selection().shopName(), value.selection().rowIds(), value.selection().fingerprint(),
                value.base().lines(), quote == null ? null : quote.providerCode(),
                quote == null ? null : quote.id(), quote == null ? null : quote.expiresAt(),
                value.base().subtotal(), value.base().productDiscountAmount(),
                pricing == null ? null : pricing.merchandiseDiscountAmount(),
                quote == null ? null : quote.fee(), pricing == null ? null : pricing.shippingDiscountAmount(),
                pricing == null ? null : pricing.shippingFee(), pricing == null ? null : pricing.totalAmount(),
                pricing != null, pricing == null ? value.error() : null);
    }

    private MultiShopCheckoutPreviewResponse.GroupTotals groupTotals(Collection<ShopPricing> pricing) {
        try {
            long subtotal = 0, productDiscount = 0, voucherDiscount = 0;
            long shippingBefore = 0, shippingDiscount = 0, shippingFee = 0, total = 0;
            for (ShopPricing child : pricing) {
                subtotal = Math.addExact(subtotal, child.base().subtotal());
                productDiscount = Math.addExact(productDiscount, child.base().productDiscountAmount());
                voucherDiscount = Math.addExact(voucherDiscount, child.merchandiseDiscountAmount());
                shippingBefore = Math.addExact(shippingBefore, child.quote().fee());
                shippingDiscount = Math.addExact(shippingDiscount, child.shippingDiscountAmount());
                shippingFee = Math.addExact(shippingFee, child.shippingFee());
                total = Math.addExact(total, child.totalAmount());
            }
            return new MultiShopCheckoutPreviewResponse.GroupTotals(subtotal, productDiscount, voucherDiscount,
                    shippingBefore, shippingDiscount, shippingFee, total);
        } catch (ArithmeticException overflow) {
            throw new BusinessConflictException("Tổng tiền của nhóm vượt giới hạn được hỗ trợ.");
        }
    }

    private String previewRevision(MultiShopCheckoutSelectionResolver.Selection selection,
            MultiShopCheckoutRequest request, AddressSnapshot destination, List<PreviewShop> pricing) {
        StringBuilder canonical = new StringBuilder("multi-shop-preview-v1;");
        append(canonical, selection.revision());
        append(canonical, request.paymentMethod().name());
        append(canonical, Objects.toString(destination.shipInfoId(), ""));
        append(canonical, destination.shipping().recipientName());
        append(canonical, destination.shipping().phone());
        append(canonical, destination.shipping().addressLine());
        append(canonical, destination.resolved().catalogSource());
        append(canonical, destination.resolved().provinceCode());
        append(canonical, destination.resolved().wardCode());
        for (PreviewShop value : pricing.stream().sorted(Comparator.comparing(shop -> shop.selection().shopId())).toList()) {
            append(canonical, value.selection().shopId().toString());
            append(canonical, value.selection().fingerprint());
            append(canonical, code(value.options().discountCode()));
            append(canonical, code(value.options().shippingDiscountCode()));
            append(canonical, trimNote(value.options().customerNote()));
            ShopPricing shop = value.pricing();
            QuoteSnapshot quote = value.quote();
            append(canonical, quote == null ? "" : quote.id().toString());
            append(canonical, quote == null ? "" : quote.providerCode());
            append(canonical, quote == null ? "" : Long.toString(quote.fee()));
            append(canonical, quote == null ? "" : quote.expiresAt().toString());
            if (shop == null) {
                append(canonical, "NOT_READY");
                append(canonical, value.error());
            } else {
                append(canonical, Long.toString(shop.base().subtotal()));
                append(canonical, Long.toString(shop.base().productDiscountAmount()));
                append(canonical, Long.toString(shop.merchandiseDiscountAmount()));
                append(canonical, Long.toString(shop.shippingDiscountAmount()));
                append(canonical, Long.toString(shop.shippingFee()));
                append(canonical, Long.toString(shop.totalAmount()));
                append(canonical, code(shop.discounts().merchandise().appliedCode()));
                append(canonical, code(shop.discounts().shipping().appliedCode()));
            }
        }
        return sha256(canonical.toString());
    }

    private MultiShopCheckoutResponse replay(UUID customerId, String key, String requestHash) {
        CheckoutGroupEntity group = groups.findByCustomerIdAndIdempotencyKey(customerId, key)
                .orElseThrow(() -> new BusinessConflictException("Yêu cầu cùng khoá đang được xử lý."));
        if (!Objects.equals(group.getRequestHash(), requestHash)) {
            throw new BusinessConflictException("Khoá idempotency đã được dùng cho một nội dung yêu cầu khác.");
        }
        List<OrderEntity> children = orders.findByCheckoutGroupIdOrderByCreatedAtAsc(group.getId());
        if (children.isEmpty()) throw new BusinessConflictException("Yêu cầu cùng khoá đang được xử lý.");
        metrics.orderReplay();
        return toResponse(group, children);
    }

    private MultiShopCheckoutResponse toResponse(CheckoutGroupEntity group, List<OrderEntity> childOrders) {
        List<MultiShopCheckoutResponse.ChildOrder> children = childOrders.stream()
                .sorted(Comparator.comparing(OrderEntity::getShopId).thenComparing(OrderEntity::getId))
                .map(order -> {
                    List<OrderFulfillmentEntity> rows = fulfillments.findByOrderIdOrderByCreatedAtAsc(order.getId());
                    OverallFulfillmentStatus fulfillment = OverallFulfillmentStatus.from(rows.stream()
                            .map(OrderFulfillmentEntity::getStatus).toList());
                    return new MultiShopCheckoutResponse.ChildOrder(order.getId(), order.getOrderNumber(),
                            order.getShopId(), order.getShopNameSnapshot(), order.getTotalAmount(),
                            order.getPaymentStatus(), fulfillment);
                }).toList();
        PaymentMethod method = null;
        try { method = PaymentMethod.valueOf(group.getPaymentMethod()); }
        catch (RuntimeException ignored) { }
        return new MultiShopCheckoutResponse(group.getId(), group.getGroupNumber(), group.getTotalAmount(),
                group.getCurrency(), method, group.getExpiresAt(), group.getPaymentStatus(),
                group.getFulfillmentStatus(), children);
    }

    private String requiredIdempotencyKey(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > 120) {
            throw new BusinessConflictException("Khoá idempotency hợp lệ là bắt buộc.");
        }
        return normalized;
    }

    private String generateGroupNumber() {
        return "WTC" + NUMBER_DATE.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private String generateOrderNumber() {
        return "WT" + NUMBER_DATE.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private String childIdempotencyKey(UUID groupId, UUID shopId) {
        return "mco:" + groupId.toString().replace("-", "") + ":" + shopId.toString().replace("-", "");
    }

    private String idsRevision(List<UUID> ids) {
        return sha256(ids.stream().map(UUID::toString).sorted().reduce("", (left, right) -> left + ";" + right));
    }

    private static String orderItemName(String name, String customization) {
        if (customization == null) return name;
        String suffix = " (" + customization + ")";
        String base = name == null ? "Sản phẩm" : name;
        int maxBaseLength = Math.max(0, 120 - suffix.length());
        if (base.length() > maxBaseLength) base = base.substring(0, maxBaseLength).stripTrailing();
        return base + suffix;
    }

    private String required(String value, String message) {
        String result = value == null ? null : value.trim();
        if (result == null || result.isBlank()) throw new BusinessConflictException(message);
        return result;
    }

    private static String trimNote(String value) {
        if (value == null) return "";
        String note = value.trim();
        return note.length() > 500 ? note.substring(0, 500) : note;
    }

    private static void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(':').append(safe).append(';');
    }

    private static String code(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeError(String value) {
        if (value == null || value.isBlank()) return "Báo giá hoặc voucher chưa sẵn sàng.";
        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private record AddressSnapshot(ShippingAddressRequest shipping, UUID shipInfoId,
            AdministrativeAddressService.ResolvedAddress resolved, ShippingAddressResponse response) { }
    private record QuoteSnapshot(UUID id, String providerCode, long fee, Instant expiresAt) { }
    private record BasePricing(long subtotal, long productDiscountAmount,
            List<MultiShopCheckoutPreviewResponse.ItemLine> lines, List<OrderItemEntity> items) { }
    private record ShopPricing(MultiShopCheckoutSelectionResolver.ShopSelection selection,
            MultiShopCheckoutRequest.ShopOptions options, BasePricing base, QuoteSnapshot quote,
            DiscountPolicy.CheckoutDiscounts discounts, long merchandiseDiscountAmount,
            long shippingDiscountAmount, long shippingFee, long totalAmount) {
        private boolean ready() { return quote != null; }
    }
    private record PreviewShop(MultiShopCheckoutSelectionResolver.ShopSelection selection,
            MultiShopCheckoutRequest.ShopOptions options, BasePricing base, QuoteSnapshot quote,
            ShopPricing pricing, String error) { }
    private record ProductReservation(ProductEntity product, int quantity) { }
    private record PlacedChild(OrderEntity order, UUID shopId) { }
    private record VoucherReservation(OrderEntity order, String code, long amount) { }
}
