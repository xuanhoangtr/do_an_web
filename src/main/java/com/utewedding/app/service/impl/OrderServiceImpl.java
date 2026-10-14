package com.utewedding.app.service.impl;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.AuditAction;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentMethod;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.entity.CartEntity;
import com.utewedding.app.domain.entity.CartItemEntity;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderFulfillmentEntity;
import com.utewedding.app.domain.entity.AuditLogEntity;
import com.utewedding.app.domain.entity.OrderIdempotencyEntity;
import com.utewedding.app.domain.entity.OrderItemEntity;
import com.utewedding.app.domain.entity.PaymentEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.domain.entity.ShipInfoEntity;
import com.utewedding.app.dto.request.CreateOrderRequest;
import com.utewedding.app.dto.request.PaymentRequest;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import com.utewedding.app.dto.response.OrderItemResponse;
import com.utewedding.app.dto.response.OrderResponse;
import com.utewedding.app.dto.response.PublicOrderTrackingResponse;
import com.utewedding.app.dto.response.CheckoutPricingPreview;
import com.utewedding.app.dto.response.PaymentResponse;
import com.utewedding.app.dto.response.ShippingAddressResponse;
import com.utewedding.app.dto.response.FulfillmentResponse;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.CheckoutPriceChangedException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.event.OrderCreatedEvent;
import com.utewedding.app.repository.CartItemRepository;
import com.utewedding.app.repository.CartRepository;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.AuditLogRepository;
import com.utewedding.app.repository.OrderIdempotencyRepository;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.PaymentRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.ShipInfoRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.repository.WeddingRepository;
import com.utewedding.app.observability.CommerceMetrics;
import com.utewedding.app.service.OrderService;
import com.utewedding.app.service.payment.PaymentIntegrityService;
import com.utewedding.app.service.FulfillmentService;
import com.utewedding.app.service.AdministrativeAddressService;
import com.utewedding.app.service.OrderIntegrityProbe;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.util.ProductOptions;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.port.DiscountPolicy;
import com.utewedding.app.service.port.ShippingFeePolicy;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderServiceImpl implements OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);
    private static final DateTimeFormatter ORDER_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final int MAX_LINE_QUANTITY = 99;
    private static final int DEFAULT_ORDER_PAGE_SIZE = 50;
    private static final int MAX_ORDER_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderIdempotencyRepository idempotencyRepository;
    private final PaymentRepository paymentRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final WeddingRepository weddingRepository;
    private final DiscountPolicy discountPolicy;
    private final AuditLogPort auditLog;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;
    private final UserRepository userRepository;
    private final ShippingFeePolicy shippingFeePolicy;
    private final ShipInfoRepository shipInfoRepository;
    private final FulfillmentService fulfillmentService;
    private final AdministrativeAddressService addressDirectory;
    private final OrderFulfillmentRepository fulfillmentRepository;
    private AuditLogRepository auditLogRepository;
    private CommerceMetrics metrics = CommerceMetrics.noop();
    private final OrderIntegrityProbe integrityProbe;
    private PaymentIntegrityService paymentIntegrity;
    @Value("${orders.payment.vnpay-unpaid-ttl:24h}")
    private Duration vnpayUnpaidTtl = Duration.ofHours(24);
    private ShippingQuoteService shippingQuotes;
    private OrderShippingSnapshotService shippingSnapshots;
    private AccountingDocumentService accountingDocuments;
    private CodReconciliationService codReconciliation;
    private RiskService riskService;
    private TransactionTemplate refundTransactions;

    public OrderServiceImpl(OrderRepository orders, OrderItemRepository items,
                            OrderIdempotencyRepository claims, PaymentRepository payments,
                            CartRepository carts, CartItemRepository cartItems,
                            ProductRepository products, WeddingRepository weddings,
                            @Qualifier("voucherDiscountPolicy") DiscountPolicy discounts, AuditLogPort audit,
                            EntityManager manager, ApplicationEventPublisher events,
                             UserRepository users, ShippingFeePolicy fees, ShipInfoRepository addresses,
                             FulfillmentService fulfillments) {
        this(orders, items, claims, payments, carts, cartItems, products, weddings, discounts, audit,
                manager, events, users, fees, addresses, fulfillments, null, null, null);
    }

    public OrderServiceImpl(OrderRepository orders, OrderItemRepository items,
                            OrderIdempotencyRepository claims, PaymentRepository payments,
                            CartRepository carts, CartItemRepository cartItems,
                            ProductRepository products, WeddingRepository weddings,
                            @Qualifier("voucherDiscountPolicy") DiscountPolicy discounts, AuditLogPort audit,
                            EntityManager manager, ApplicationEventPublisher events,
                            UserRepository users, ShippingFeePolicy fees, ShipInfoRepository addresses,
                            FulfillmentService fulfillments, AdministrativeAddressService addressDirectory) {
        this(orders, items, claims, payments, carts, cartItems, products, weddings, discounts, audit,
                manager, events, users, fees, addresses, fulfillments, addressDirectory, null, null);
    }

    @Autowired
    public OrderServiceImpl(OrderRepository orders, OrderItemRepository items,
                            OrderIdempotencyRepository claims, PaymentRepository payments,
                            CartRepository carts, CartItemRepository cartItems,
                            ProductRepository products, WeddingRepository weddings,
                            @Qualifier("voucherDiscountPolicy") DiscountPolicy discounts, AuditLogPort audit,
                            EntityManager manager, ApplicationEventPublisher events,
                            UserRepository users, ShippingFeePolicy fees, ShipInfoRepository addresses,
                            FulfillmentService fulfillments, AdministrativeAddressService addressDirectory,
                            OrderFulfillmentRepository fulfillmentRepository,
                            @Nullable OrderIntegrityProbe integrityProbe) {
        orderRepository = orders; orderItemRepository = items; idempotencyRepository = claims;
        paymentRepository = payments; cartRepository = carts; cartItemRepository = cartItems;
        productRepository = products; weddingRepository = weddings; discountPolicy = discounts;
        auditLog = audit; entityManager = manager; eventPublisher = events; userRepository = users;
        shippingFeePolicy = fees == null ? new DefaultShippingFeePolicy(0) : fees;
        shipInfoRepository = addresses;
        fulfillmentService = fulfillments;
        this.addressDirectory = addressDirectory;
        this.fulfillmentRepository = fulfillmentRepository;
        this.integrityProbe = integrityProbe;
    }

    @Autowired(required = false)
    void attachMetrics(CommerceMetrics metrics) {
        this.metrics = metrics == null ? CommerceMetrics.noop() : metrics;
    }

    @Autowired(required = false)
    void attachPaymentIntegrity(PaymentIntegrityService paymentIntegrity) {
        this.paymentIntegrity = paymentIntegrity;
    }

    @Autowired(required = false)
    void attachTrackingAuditRepository(AuditLogRepository repository) {
        this.auditLogRepository = repository;
    }

    @Autowired(required = false)
    void attachShippingP2(ShippingQuoteService quotes, OrderShippingSnapshotService snapshots,
                          AccountingDocumentService documents, CodReconciliationService cod) {
        this.shippingQuotes = quotes; this.shippingSnapshots = snapshots;
        this.accountingDocuments = documents; this.codReconciliation = cod;
    }

    @Autowired(required = false)
    void attachRiskService(RiskService service) { this.riskService = service; }

    @Autowired(required = false)
    void attachRefundTransactionManager(PlatformTransactionManager manager) {
        if (manager != null) this.refundTransactions = new TransactionTemplate(manager);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse create(UUID userId, CreateOrderRequest request, RequestContext context) {
        // Idempotency is coordinated by the unique database claim and the
        // transaction/row lock. A JVM lock would fail as soon as the app is
        // deployed on more than one instance.
        try {
            return createLocked(userId, request, context);
        } catch (BusinessConflictException | CheckoutPriceChangedException | ResourceNotFoundException
                | AccessDeniedException | ResponseStatusException ex) {
            metrics.orderRejected();
            throw ex;
        } catch (RuntimeException ex) {
            metrics.orderError();
            log.error("Order creation failed");
            throw ex;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutPricingPreview preview(UUID userId, CreateOrderRequest request) {
        CartEntity cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessConflictException("Giỏ hàng đang trống."));
        List<CartItemEntity> cartItems = cartItemRepository.findByCartIdOrderByIdAsc(cart.getId());
        if (cartItems.isEmpty()) throw new BusinessConflictException("Giỏ hàng đang trống.");
        return calculatePricing(userId, request, cartItems, false).preview();
    }

    private OrderResponse createLocked(UUID userId, CreateOrderRequest request, RequestContext context) {
        String key = requireIdempotencyKey(request.idempotencyKey());
        String requestHash = CheckoutRequestHasher.hash(userId, request);
        OrderResponse replay = replayIfPresent(userId, key, requestHash);
        if (replay != null) return replay;
        if (integrityProbe != null) integrityProbe.beforeIdempotencyClaim();
        if (idempotencyRepository == null
                || idempotencyRepository.claimIfAbsent(key, userId, requestHash) == 0) {
            OrderResponse concurrent = replayIfPresent(userId, key, requestHash);
            if (concurrent != null) return concurrent;
            throw new BusinessConflictException("Yêu cầu cùng khoá đang được xử lý.");
        }
        if (request.weddingId() != null && weddingRepository.findByIdAndOwnerId(request.weddingId(), userId).isEmpty())
            throw new ResourceNotFoundException("Không tìm thấy thiệp cưới.");
        CartEntity cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessConflictException("Giỏ hàng đang trống."));
        List<CartItemEntity> cartItems = cartItemRepository.findByCartIdOrderByIdAsc(cart.getId());
        if (cartItems.isEmpty()) throw new BusinessConflictException("Giỏ hàng đang trống.");

        PricingCalculation pricing = calculatePricing(userId, request, cartItems, true);
        CheckoutPricingPreview quote = pricing.preview();
        reserveInventory(pricing.reservations());
        if (integrityProbe != null) integrityProbe.afterInventoryReserved();
        List<OrderItemEntity> items = pricing.items();
        long subtotal = quote.subtotal();
        long discount = Math.addExact(quote.productDiscountAmount(), quote.voucherDiscountAmount());
        long codeDiscount = quote.getVoucherBenefitAmount();
        ShippingAddressRequest shipping = pricing.shipping();
        AdministrativeAddressService.ResolvedAddress resolved = addressDirectory == null
                ? null : addressDirectory.resolve(shipping.provinceCode(), shipping.wardCode());
        PaymentMethod method = request.paymentMethod() == null ? PaymentMethod.COD : request.paymentMethod();

        OrderEntity order = new OrderEntity(userId, Instant.now());
        order.setWeddingId(request.weddingId());
        order.setShipInfoId(request.shippingAddressId());
        order.setOrderNumber(generateOrderNumber());
        // Payment is the only public order lifecycle. COD is a payment method,
        // not a fulfillment state, so checkout always creates PENDING.
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.PENDING);
        // Online payment reservations expire while unpaid. COD has a pending
        // payment record by design, but its fulfillment starts immediately and
        // must not be auto-expired by the payment timeout job.
        order.setExpiresAt(method == PaymentMethod.COD ? null
                : method == PaymentMethod.VNPAY ? Instant.now().plus(vnpayUnpaidTtl)
                : Instant.now().plusSeconds(900));
        order.setSubtotal(subtotal);
        order.setDiscountAmount(discount);
        order.setShippingFee(quote.shippingFee());
        order.setShippingFeeBeforeDiscount(quote.shippingFeeBeforeDiscount());
        order.setShippingDiscountAmount(quote.shippingDiscountAmount());
        order.setVoucherDiscountAmount(quote.voucherDiscountAmount());
        order.setVoucherType(quote.voucherType());
        order.setTotalAmount(quote.totalAmount());
        order.setCurrency("VND");
        order.setDiscountCode(quote.appliedVoucherCode());
        order.setIdempotencyKey(key);
        order.setPaymentMethod(method.name());
        order.setShippingRecipientName(required(shipping.recipientName(), "Tên người nhận là bắt buộc."));
        order.setShippingPhone(required(shipping.phone(), "Số điện thoại là bắt buộc."));
        order.setShippingAddress(required(shipping.addressLine(), "Địa chỉ giao hàng là bắt buộc."));
        if (resolved == null) {
            order.setShippingWard(normalize(shipping.ward()));
            order.setShippingDistrict(normalize(shipping.district()));
            order.setShippingProvince(normalize(shipping.province()));
        } else {
            order.setShippingWard(resolved.wardName());
            order.setShippingProvince(resolved.provinceName());
            order.setShippingWardCode(resolved.wardCode());
            order.setShippingWardName(resolved.wardName());
            order.setShippingProvinceCode(resolved.provinceCode());
            order.setShippingProvinceName(resolved.provinceName());
            order.setShippingAddressCatalogSource(resolved.catalogSource());
        }
        order = orderRepository.save(order);
        entityManager.flush();

        PaymentEntity pending = new PaymentEntity();
        pending.setOrderId(order.getId());
        pending.setStatus(PaymentStatus.PENDING);
        pending.setAmount(order.getTotalAmount());
        pending.setCurrency(order.getCurrency());
        pending.setProvider(method.name());
        paymentRepository.save(pending);

        OrderIdempotencyEntity claim = idempotencyRepository.findById(key)
                .orElseThrow(() -> new BusinessConflictException("Không thể lưu claim idempotency."));
        claim.setOrderId(order.getId());
        idempotencyRepository.save(claim);
        for (OrderItemEntity item : items) {
            item.setOrderId(order.getId());
        }
        orderItemRepository.saveAll(items);
        entityManager.flush();
        if (shippingSnapshots != null) shippingSnapshots.create(order, request.shippingQuoteId(), shipping);
        if (accountingDocuments != null) {
            accountingDocuments.ensure(order, com.utewedding.app.domain.AccountingDocumentType.ORDER_CONFIRMATION, null);
        }
        if (method == PaymentMethod.COD && codReconciliation != null) {
            codReconciliation.initialize(order.getId());
        }
        cartItemRepository.deleteByCartId(cart.getId());
        discountPolicy.reserve(quote.appliedVoucherCode(), userId, order.getId(), codeDiscount);
        entityManager.flush();
        auditLog.record("ORDER_CREATED", userId, "Order", order.getId(),
                Map.of("totalAmount", order.getTotalAmount(), "discountAmount", discount), context);
        if (riskService != null) riskService.assessOrder(userId, order.getId(), order.getTotalAmount());
        eventPublisher.publishEvent(new OrderCreatedEvent(userId, order.getOrderNumber(),
                order.getTotalAmount(), order.getCurrency()));
        OrderResponse created = toResponse(order);
        metrics.orderCreated(method.name());
        return created;
    }

    private PricingCalculation calculatePricing(UUID userId, CreateOrderRequest request,
                                               List<CartItemEntity> cartItems, boolean lockProducts) {
        long subtotal = 0;
        long productDiscountTotal = 0;
        List<ProductReservation> reservations = new ArrayList<>();
        List<OrderItemEntity> items = new ArrayList<>();
        List<CheckoutPricingPreview.Line> lines = new ArrayList<>();
        for (CartItemEntity cartItem : cartItems.stream()
                .sorted(Comparator.comparing(CartItemEntity::getProductId)).toList()) {
            ProductEntity product = (lockProducts
                    ? productRepository.findByIdForUpdate(cartItem.getProductId())
                    : productRepository.findByIdAndActiveTrue(cartItem.getProductId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không còn kinh doanh."));
            int quantity = cartItem.getQuantity();
            if (quantity < 1 || quantity > MAX_LINE_QUANTITY || quantity > product.getStockQuantity()) {
                throw new BusinessConflictException("Tồn kho của sản phẩm đã thay đổi, vui lòng cập nhật giỏ hàng.");
            }
            if (quantity < product.getMinOrderQuantity()) {
                throw new BusinessConflictException("Số lượng tối thiểu để đặt sản phẩm này là "
                        + product.getMinOrderQuantity() + ".");
            }
            long gross = Math.multiplyExact(product.getUnitPrice(), quantity);
            long lineDiscount = Math.multiplyExact(gross, product.getDiscountPercent()) / 100;
            long lineTotal = gross - lineDiscount;
            subtotal = Math.addExact(subtotal, gross);
            productDiscountTotal = Math.addExact(productDiscountTotal, lineDiscount);
            reservations.add(new ProductReservation(product, quantity));
            lines.add(new CheckoutPricingPreview.Line(product.getId(), product.getSku(),
                    product.getUnitPrice(), quantity, product.getDiscountPercent(),
                    lineDiscount, lineTotal));

            String customizationText = ProductOptions.resolvePhotoSize(
                    product.getCustomizationGuide(), cartItem.getCustomizationText());
            OrderItemEntity item = new OrderItemEntity();
            item.setProductId(product.getId());
            item.setProductName(orderItemName(product.getName(), customizationText));
            item.setUnitPrice(product.getUnitPrice());
            item.setQuantity(quantity);
            item.setDiscountAmount(lineDiscount);
            item.setLineTotal(lineTotal);
            items.add(item);
        }

        long eligible = subtotal - productDiscountTotal;
        ShippingAddressRequest shipping = resolveShipping(userId, request);
        long shippingFeeBeforeDiscount;
        String shippingProviderCode = null;
        if (request.shippingQuoteId() != null && shippingQuotes != null) {
            var shippingQuote = shippingQuotes.requireValid(userId, request.shippingQuoteId(), cartItems, shipping);
            shippingFeeBeforeDiscount = shippingQuote.getFee();
            shippingProviderCode = shippingQuote.getProviderCode();
        } else {
            if (shippingQuotes == null) {
                throw new BusinessConflictException(
                        "Không thể xác định đơn vị và phí giao hàng. Vui lòng tải lại báo giá vận chuyển.");
            } else {
                var address = addressDirectory == null ? null
                        : addressDirectory.resolve(shipping.provinceCode(), shipping.wardCode());
                var route = address == null
                        ? shippingQuotes.carrierRouteFor(
                                addressDirectory == null ? null : addressDirectory.activeCatalogSource(),
                                shipping.provinceCode())
                        : shippingQuotes.carrierRouteFor(address.catalogSource(), address.provinceCode());
                shippingFeeBeforeDiscount = route.customerFee();
                shippingProviderCode = route.providerCode();
            }
        }
        DiscountPolicy.DiscountResult voucher;
        if (discountPolicy instanceof VoucherDiscountPolicy) {
            voucher = lockProducts
                    ? discountPolicy.calculateForCheckout(request.discountCode(), eligible,
                            shippingFeeBeforeDiscount, shippingProviderCode, userId)
                    : discountPolicy.previewForCheckout(request.discountCode(), eligible,
                            shippingFeeBeforeDiscount, shippingProviderCode, userId);
        } else {
            voucher = discountPolicy.calculate(request.discountCode(), eligible);
        }
        boolean shippingVoucher = voucher.type() != null && voucher.type().isShippingVoucher();
        long voucherDiscount = shippingVoucher ? 0 : Math.max(0, Math.min(eligible, voucher.amount()));
        long shippingDiscount = shippingVoucher
                ? Math.max(0, Math.min(shippingFeeBeforeDiscount, voucher.amount())) : 0;
        long shippingFee = shippingFeeBeforeDiscount - shippingDiscount;
        allocateVoucher(items, voucherDiscount);
        CheckoutPricingPreview preview = new CheckoutPricingPreview(subtotal, productDiscountTotal,
                voucherDiscount, shippingFee,
                Math.addExact(eligible - voucherDiscount, shippingFee),
                voucher.appliedCode(), lines, shippingFeeBeforeDiscount, shippingDiscount, voucher.type());
        if (request.expectedTotalAmount() != null
                && request.expectedTotalAmount().longValue() != preview.totalAmount()) {
            throw new CheckoutPriceChangedException(preview);
        }
        return new PricingCalculation(preview, List.copyOf(reservations), List.copyOf(items), shipping);
    }

    /** Stable largest-remainder allocation; all item snapshots add up to the order voucher exactly. */
    private void allocateVoucher(List<OrderItemEntity> items, long voucherDiscount) {
        long total = items.stream().mapToLong(OrderItemEntity::getLineTotal).sum();
        if (items.isEmpty()) return;
        if (voucherDiscount == 0 || total == 0) {
            items.forEach(item -> { item.setVoucherDiscountAllocated(0); item.setNetPaidAmount(item.getLineTotal()); });
            return;
        }
        record Remainder(OrderItemEntity item, long remainder) {}
        List<Remainder> remainders = new ArrayList<>(); long assigned = 0;
        for (OrderItemEntity item : items) {
            long product = Math.multiplyExact(voucherDiscount, item.getLineTotal());
            long share = product / total; assigned = Math.addExact(assigned, share);
            item.setVoucherDiscountAllocated(share); remainders.add(new Remainder(item, product % total));
        }
        remainders.sort(Comparator.comparingLong(Remainder::remainder).reversed()
                .thenComparing(row -> row.item().getProductId()));
        for (long i = 0; i < voucherDiscount - assigned; i++) {
            OrderItemEntity item = remainders.get((int) (i % remainders.size())).item();
            item.setVoucherDiscountAllocated(item.getVoucherDiscountAllocated() + 1);
        }
        items.forEach(item -> item.setNetPaidAmount(item.getLineTotal() - item.getVoucherDiscountAllocated()));
    }

    private ShippingAddressRequest resolveShipping(UUID userId, CreateOrderRequest request) {
        if (request.shippingAddressId() != null && shipInfoRepository != null) {
            ShipInfoEntity info = shipInfoRepository.findByIdAndUserId(request.shippingAddressId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy địa chỉ giao hàng."));
            if (addressDirectory != null
                    && !java.util.Objects.equals(info.getAddressCatalogSource(), addressDirectory.activeCatalogSource())) {
                if (request.shipping() == null) {
                    throw new BusinessConflictException("Địa chỉ đã lưu dùng danh mục cũ. Hãy chọn lại tỉnh/thành và phường/xã.");
                }
                return request.shipping();
            }
            if (addressDirectory != null && (info.getProvinceCode() == null || info.getWardCode() == null)) {
                if (request.shipping() == null) {
                    throw new BusinessConflictException("Địa chỉ đã lưu cần chọn lại tỉnh/thành và phường/xã.");
                }
                return request.shipping();
            }
            ShippingAddressRequest saved = addressDirectory == null
                    ? new ShippingAddressRequest(info.getRecipientName(), info.getPhone(), info.getAddressLine(),
                        info.getWard(), info.getDistrict(), info.getProvince())
                    : new ShippingAddressRequest(info.getRecipientName(), info.getPhone(), info.getAddressLine(),
                        info.getProvinceCode(), info.getWardCode());
            if (request.shipping() != null && !sameShippingSnapshot(request.shipping(), saved)) {
                return request.shipping();
            }
            return saved;
        }
        if (request.shipping() == null) throw new BusinessConflictException("Thông tin giao hàng là bắt buộc.");
        return request.shipping();
    }

    private boolean sameShippingSnapshot(ShippingAddressRequest left, ShippingAddressRequest right) {
        return same(left.recipientName(), right.recipientName())
                && same(left.phone(), right.phone())
                && same(left.addressLine(), right.addressLine())
                && same(left.provinceCode(), right.provinceCode())
                && same(left.wardCode(), right.wardCode());
    }

    private boolean same(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ")
                .toLowerCase(java.util.Locale.ROOT);
    }

    @Override @Transactional(readOnly = true)
    public OrderResponse get(UUID userId, UUID orderId) {
        return toResponse(orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng.")));
    }

    @Override @Transactional(readOnly = true)
    public OrderResponse getByOrderNumber(UUID userId, String number) {
        return toResponse(orderRepository.findByOrderNumberAndUserId(normalize(number), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng.")));
    }

    @Override
    @Transactional(readOnly = true)
    public UUID getTrackingAccessCode(UUID userId, UUID orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .map(OrderEntity::getTrackingAccessCode)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
    }

    @Override
    @Transactional(readOnly = true)
    public PublicOrderTrackingResponse getPublicTracking(UUID trackingAccessCode) {
        OrderEntity order = trackingAccessCode == null ? null
                : orderRepository.findByTrackingAccessCode(trackingAccessCode).orElse(null);
        if (order == null) throw new ResourceNotFoundException("Không tìm thấy đơn hàng.");
        List<OrderFulfillmentEntity> fulfillmentRows = fulfillmentRepository == null
                ? List.of() : fulfillmentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        List<PublicOrderTrackingResponse.Fulfillment> shipmentStatus = fulfillmentRows.stream()
                .map(row -> new PublicOrderTrackingResponse.Fulfillment(
                        row.getStatus(), row.getTrackingNumber(), row.getUpdatedAt()))
                .toList();
        List<PublicOrderTrackingResponse.TimelineEvent> timeline = trackingTimeline(order, fulfillmentRows);
        DeliveryEstimate estimate = deliveryEstimate(order, fulfillmentRows);
        return new PublicOrderTrackingResponse(order.getOrderNumber(), order.getStatus(),
                order.getCreatedAt(), shipmentStatus, timeline, estimate == null ? null : estimate.from(),
                estimate == null ? null : estimate.to(), estimate == null ? 0 : estimate.minDays(),
                estimate == null ? 0 : estimate.maxDays(), estimate == null ? null : estimate.area());
    }

    private List<PublicOrderTrackingResponse.TimelineEvent> trackingTimeline(
            OrderEntity order, List<OrderFulfillmentEntity> fulfillmentRows) {
        List<PublicOrderTrackingResponse.TimelineEvent> events = new ArrayList<>();
        addTrackingEvent(events, "Đơn hàng đã được đặt", null, order.getCreatedAt(), false);

        List<AuditLogEntity> orderEvents = auditLogRepository == null ? List.of()
                : auditLogRepository.findByTargetTypeAndTargetIdAndActionInOrderByCreatedAtAsc(
                        "Order", order.getId(), Set.of(AuditAction.ORDER_CONFIRMED,
                                AuditAction.ORDER_CANCELLED, AuditAction.ORDER_STATUS_CHANGED));
        boolean confirmationAuditFound = false;
        for (AuditLogEntity event : orderEvents) {
            if (event.getAction() == AuditAction.ORDER_CONFIRMED) {
                confirmationAuditFound = true;
                addTrackingEvent(events, "Cửa hàng đã xác nhận đơn", null, event.getCreatedAt(), false);
            } else if (event.getAction() == AuditAction.ORDER_CANCELLED) {
                addTrackingEvent(events, "Đơn hàng đã bị hủy", null, event.getCreatedAt(), true);
            } else {
                String target = metadataText(event.getMetadata(), "to");
                if ("CANCELLED".equals(target) || "EXPIRED".equals(target) || "REFUNDED".equals(target)) {
                    addTrackingEvent(events, orderStatusTitle(target), null, event.getCreatedAt(), true);
                }
            }
        }
        if (order.getConfirmedAt() != null && !confirmationAuditFound) {
            addTrackingEvent(events, "Cửa hàng đã xác nhận đơn", null, order.getConfirmedAt(), false);
        }

        Set<UUID> fulfillmentIds = fulfillmentRows.stream().map(OrderFulfillmentEntity::getId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<UUID, List<AuditLogEntity>> fulfillmentEvents = new HashMap<>();
        if (auditLogRepository != null && !fulfillmentIds.isEmpty()) {
            for (AuditLogEntity event : auditLogRepository
                    .findByTargetTypeAndTargetIdInAndActionInOrderByCreatedAtAsc("OrderFulfillment",
                            fulfillmentIds, Set.of(AuditAction.FULFILLMENT_CREATED,
                                    AuditAction.FULFILLMENT_STATUS_CHANGED))) {
                fulfillmentEvents.computeIfAbsent(event.getTargetId(), ignored -> new ArrayList<>()).add(event);
            }
        }

        for (OrderFulfillmentEntity fulfillment : fulfillmentRows) {
            List<AuditLogEntity> history = fulfillmentEvents.getOrDefault(fulfillment.getId(), List.of());
            FulfillmentStatus lastKnownStatus = null;
            for (AuditLogEntity event : history) {
                if (event.getAction() == AuditAction.FULFILLMENT_CREATED) {
                    lastKnownStatus = FulfillmentStatus.CONFIRMED;
                    addTrackingEvent(events, "Đơn được tiếp nhận để chuẩn bị hàng", null,
                            event.getCreatedAt(), false);
                    continue;
                }
                String target = metadataText(event.getMetadata(), "to");
                FulfillmentStatus status = fulfillmentStatus(target);
                if (status == null) continue;
                lastKnownStatus = status;
                addTrackingEvent(events, fulfillmentStatusTitle(status), null, event.getCreatedAt(),
                        isProblemStatus(status));
            }
            if (fulfillment.getStatus() != null && fulfillment.getStatus() != lastKnownStatus) {
                addTrackingEvent(events, fulfillmentStatusTitle(fulfillment.getStatus()),
                        "Trạng thái giao hàng hiện tại", fulfillment.getUpdatedAt(),
                        isProblemStatus(fulfillment.getStatus()));
            }
        }
        events.sort(java.util.Comparator.comparing(PublicOrderTrackingResponse.TimelineEvent::occurredAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return List.copyOf(events);
    }

    private DeliveryEstimate deliveryEstimate(OrderEntity order, List<OrderFulfillmentEntity> fulfillmentRows) {
        if (order.getCreatedAt() == null || order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.EXPIRED || order.getStatus() == OrderStatus.REFUNDED
                || (!fulfillmentRows.isEmpty() && fulfillmentRows.stream()
                        .allMatch(row -> row.getStatus() == FulfillmentStatus.DELIVERED))) return null;
        boolean local = shippingQuotes != null
                && "INTERNAL".equals(shippingQuotes.routeFor(order.getShippingAddressCatalogSource(),
                        order.getShippingProvinceCode()).providerCode());
        int minimumDays = local ? 1 : 3;
        int maximumDays = local ? 2 : 5;
        LocalDate baseDate = order.getCreatedAt().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        String area = local ? "TP. Hồ Chí Minh" : "ngoại tỉnh";
        return new DeliveryEstimate(baseDate.plusDays(minimumDays), baseDate.plusDays(maximumDays),
                minimumDays, maximumDays, area);
    }

    private void addTrackingEvent(List<PublicOrderTrackingResponse.TimelineEvent> events, String title,
                                  String detail, Instant occurredAt, boolean warning) {
        if (occurredAt != null) events.add(new PublicOrderTrackingResponse.TimelineEvent(
                title, detail, occurredAt, warning));
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? null : value.toString();
    }

    private String orderStatusTitle(String status) {
        return switch (status) {
            case "CANCELLED" -> "Đơn hàng đã bị hủy";
            case "EXPIRED" -> "Đơn hàng đã hết hạn";
            case "REFUNDED" -> "Đơn hàng đã được hoàn tiền";
            default -> "Trạng thái đơn hàng đã thay đổi";
        };
    }

    private String fulfillmentStatusTitle(FulfillmentStatus status) {
        return switch (status) {
            case CONFIRMED -> "Đơn được tiếp nhận để chuẩn bị hàng";
            case PROCESSING -> "Cửa hàng đang chuẩn bị hàng";
            case PICKUP_PENDING -> "Đang chờ đơn vị vận chuyển nhận hàng";
            case PICKED_UP -> "Đơn vị vận chuyển đã nhận hàng";
            case IN_TRANSIT, SHIPPING -> "Đơn hàng đang trên đường giao";
            case DELIVERED -> "Giao hàng thành công";
            case DELIVERY_FAILED -> "Giao hàng chưa thành công";
            case RETRY_DELIVERY -> "Đơn hàng đang được giao lại";
            case RETURNING -> "Đơn hàng đang được hoàn về cửa hàng";
            case RETURNED -> "Đơn hàng đã được hoàn về cửa hàng";
            case CANCELLED -> "Lượt giao hàng đã bị hủy";
        };
    }

    private FulfillmentStatus fulfillmentStatus(String value) {
        if (value == null) return null;
        try { return FulfillmentStatus.valueOf(value); }
        catch (IllegalArgumentException exception) { return null; }
    }

    private boolean isProblemStatus(FulfillmentStatus status) {
        return status == FulfillmentStatus.DELIVERY_FAILED || status == FulfillmentStatus.RETURNING
                || status == FulfillmentStatus.RETURNED || status == FulfillmentStatus.CANCELLED;
    }

    private record DeliveryEstimate(LocalDate from, LocalDate to, int minDays, int maxDays, String area) { }

    @Override @Transactional(readOnly = true)
    public List<OrderResponse> list(UUID userId) {
        return listPage(userId, defaultOrderPage()).getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> searchMine(UUID userId, String orderNumber, OrderStatus status,
                                          PaymentStatus paymentStatus, FulfillmentStatus fulfillmentStatus,
                                          Instant from, Instant to, Pageable pageable) {
        return searchMine(userId, orderNumber, status, paymentStatus, fulfillmentStatus,
                null, from, to, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> searchMine(UUID userId, String orderNumber, OrderStatus status,
                                          PaymentStatus paymentStatus, FulfillmentStatus fulfillmentStatus,
                                          com.utewedding.app.service.web.OrderHistoryGroup historyGroup,
                                          Instant from, Instant to, Pageable pageable) {
        int page = pageable == null ? 0 : Math.max(0, pageable.getPageNumber());
        int size = pageable == null ? DEFAULT_ORDER_PAGE_SIZE
                : Math.min(MAX_ORDER_PAGE_SIZE, Math.max(1, pageable.getPageSize()));
        return mapOrders(orderRepository.searchForCustomer(userId, blankToNull(orderNumber),
                status == null ? null : status.name(), paymentStatus == null ? null : paymentStatus.name(),
                fulfillmentStatus == null ? null : fulfillmentStatus.name(),
                historyGroup == null || historyGroup == com.utewedding.app.service.web.OrderHistoryGroup.ALL
                        ? null : historyGroup.name(),
                from, to, PageRequest.of(page, size)));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<com.utewedding.app.service.web.OrderHistoryGroup, Long> countMineByGroup(UUID userId) {
        Map<com.utewedding.app.service.web.OrderHistoryGroup, Long> result = new java.util.EnumMap<>(
                com.utewedding.app.service.web.OrderHistoryGroup.class);
        for (var group : com.utewedding.app.service.web.OrderHistoryGroup.values()) result.put(group, 0L);
        long all = 0;
        for (Object[] row : orderRepository.countMineByGroup(userId)) {
            var orderStatus = com.utewedding.app.domain.OrderStatus.valueOf((String) row[0]);
            var fulfillmentStatus = com.utewedding.app.domain.OverallFulfillmentStatus.valueOf((String) row[1]);
            long count = ((Number) row[2]).longValue();
            all += count;
            var group = com.utewedding.app.service.web.OrderHistoryGroup.from(orderStatus, fulfillmentStatus);
            if (group != com.utewedding.app.service.web.OrderHistoryGroup.ALL) {
                result.merge(group, count, Long::sum);
            }
        }
        result.put(com.utewedding.app.service.web.OrderHistoryGroup.ALL, all);
        return java.util.Collections.unmodifiableMap(result);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getForOperations(UUID actorId, UUID orderId) {
        if (actorId != null) requireOperationsRole(actorId);
        return toResponse(orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng.")));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> searchForOperations(UUID actorId, String orderNumber, String buyer,
                                                   OrderStatus status, PaymentStatus paymentStatus,
                                                   FulfillmentStatus fulfillmentStatus, boolean attentionOnly,
                                                   UUID shipperId, Instant from, Instant to, Pageable pageable) {
        if (actorId != null) requireOperationsRole(actorId);
        return mapOrders(orderRepository.searchForAdmin(blankToNull(orderNumber), blankToNull(buyer), status,
                paymentStatus, attentionOnly, fulfillmentStatus, shipperId, from, to,
                normalizePageable(pageable)));
    }

    @Override @Transactional(readOnly = true)
    public Page<OrderResponse> listPage(UUID userId, Pageable pageable) {
        return mapOrders(orderRepository.findByUserId(userId, normalizePageable(pageable)));
    }

    private Page<OrderResponse> mapOrders(Page<OrderEntity> orders) {
        if (orders.isEmpty()) return orders.map(this::toResponseWithoutChildren);
        List<UUID> ids = orders.getContent().stream().map(OrderEntity::getId).toList();
        Map<UUID, List<OrderItemEntity>> items = orderItemRepository.findByOrderIdInOrderByOrderIdAscIdAsc(ids)
                .stream().collect(java.util.stream.Collectors.groupingBy(OrderItemEntity::getOrderId,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<UUID, List<PaymentEntity>> payments = paymentRepository.findByOrderIdInOrderByOrderIdAscCreatedAtDesc(ids)
                .stream().collect(java.util.stream.Collectors.groupingBy(PaymentEntity::getOrderId,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        return orders.map(order -> toResponse(order, items.getOrDefault(order.getId(), List.of()),
                payments.getOrDefault(order.getId(), List.of())));
    }

    private Pageable defaultOrderPage() {
        return PageRequest.of(0, DEFAULT_ORDER_PAGE_SIZE,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private Pageable normalizePageable(Pageable pageable) {
        int page = pageable == null ? 0 : Math.max(0, pageable.getPageNumber());
        int size = pageable == null ? DEFAULT_ORDER_PAGE_SIZE
                : Math.min(MAX_ORDER_PAGE_SIZE, Math.max(1, pageable.getPageSize()));
        Sort sort = pageable == null || pageable.getSort().isUnsorted()
                ? Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
                : pageable.getSort();
        return PageRequest.of(page, size, sort);
    }

    private OrderResponse toResponseWithoutChildren(OrderEntity order) {
        return toResponse(order, List.of(), List.of());
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public OrderResponse markPaid(UUID userId, UUID orderId, PaymentRequest request, RequestContext context) {
        OrderEntity order = orderRepository.findByIdAndUserIdForUpdate(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        entityManager.refresh(order);
        if (order.getStatus() == OrderStatus.PAID) return toResponse(order);
        if (order.getStatus() != OrderStatus.PENDING)
            throw new BusinessConflictException("Đơn hàng không còn ở trạng thái chờ thanh toán.");
        settle(order, userId, request, context, PaymentStatus.SUCCEEDED);
        return toResponse(order);
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public OrderResponse cancel(UUID userId, UUID orderId, RequestContext context) {
        return cancel(userId, orderId, null, context);
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public OrderResponse cancel(UUID userId, UUID orderId, String reason, RequestContext context) {
        if (integrityProbe != null) integrityProbe.beforeCompetingStatusWrite("cancel");
        OrderEntity order = orderRepository.findByIdAndUserIdForUpdate(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        entityManager.refresh(order);
        if (order.getStatus() == OrderStatus.CANCELLED) return toResponse(order);
        if (order.getStatus() != OrderStatus.PENDING)
            throw new BusinessConflictException("Đơn hàng không còn ở trạng thái có thể hủy.");
        cancelOpenFulfillments(orderId, userId, context);
        OrderStatus previous = order.getStatus();
        restoreStock(orderId);
        discountPolicy.release(orderId);
        applyTransition(order, OrderStatus.CANCELLED, Instant.now());
        order.setPaymentStatus(PaymentStatus.FAILED);
        if (reason != null && !reason.isBlank()) {
            order.setCancelReason(reason.trim().length() > 500 ? reason.trim().substring(0, 500) : reason.trim());
        }
        orderRepository.save(order);
        if (paymentIntegrity != null) paymentIntegrity.noteOrderClosed(order, "ORDER_CANCELLED", Instant.now());
        auditLog.record("ORDER_STATUS_CHANGED", userId, "Order", orderId,
                Map.of("from", previous.name(), "to", OrderStatus.CANCELLED.name()), context);
        auditLog.record("ORDER_CANCELLED", userId, "Order", orderId, Map.of(), context);
        return toResponse(order);
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public int expirePendingOrders(Instant now, RequestContext context) {
        Instant effectiveNow = now == null ? Instant.now() : now;
        if (paymentIntegrity != null) {
            int count = paymentIntegrity.expireDue(effectiveNow, context);
            metrics.orderExpired(count);
            return count;
        }
        int count = 0;
        for (OrderEntity order : orderRepository.findPendingExpiredForUpdate(effectiveNow)) {
            entityManager.refresh(order);
            if (order.getStatus() != OrderStatus.PENDING || order.getExpiresAt() == null
                    || order.getExpiresAt().isAfter(effectiveNow)) continue;
            restoreStock(order.getId());
            discountPolicy.release(order.getId());
            applyTransition(order, OrderStatus.EXPIRED, effectiveNow);
            order.setPaymentStatus(PaymentStatus.FAILED);
            orderRepository.save(order);
            auditLog.record("ORDER_STATUS_CHANGED", null, "Order", order.getId(),
                    Map.of("from", OrderStatus.PENDING.name(), "to", OrderStatus.EXPIRED.name()), context);
            count++;
        }
        metrics.orderExpired(count);
        return count;
    }

    @Override
    public OrderResponse refund(UUID actorId, UUID orderId, PaymentRequest request, RequestContext context) {
        return refundInternal(actorId, orderId, request, context, true);
    }

    @Override
    public OrderResponse refundReturn(UUID actorId, UUID orderId, PaymentRequest request, RequestContext context) {
        return refundInternal(actorId, orderId, request, context, false);
    }

    private OrderResponse refundInternal(UUID actorId, UUID orderId, PaymentRequest request, RequestContext context, boolean ledger) {
        try {
            var refundActor = userRepository.findById(actorId)
                    .orElseThrow(() -> new AccessDeniedException("Không có quyền hoàn tiền."));
            if (refundActor.getStatus() != com.utewedding.app.domain.UserStatus.ACTIVE
                    || refundActor.getRole() != UserRole.ADMIN && refundActor.getRole() != UserRole.SUPER_ADMIN) {
                throw new AccessDeniedException("Chỉ ADMIN/SUPER_ADMIN được hoàn tiền.");
            }
            OrderEntity snapshot = orderRepository.findById(orderId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
            if (ledger && snapshot.getStatus() == OrderStatus.REFUNDED) {
                metrics.refundReplay();
                return toResponse(snapshot);
            }
            if (ledger && request != null && request.amount() != null
                    && request.amount() < snapshot.getTotalAmount()) {
                throw new BusinessConflictException(
                        "Hoàn tiền một phần phải qua hồ sơ trả hàng để phân bổ đúng sản phẩm và ledger.");
            }
            String online = paymentIntegrity == null ? null : paymentIntegrity.onlineProvider(snapshot);
            if (online != null) {
                PaymentIntegrityService.RefundApplication application = ledger
                        ? paymentIntegrity.refund(actorId, orderId, online, request, context)
                        : paymentIntegrity.refundReturn(actorId, orderId, online, request, context);
                OrderEntity updated = orderRepository.findById(orderId)
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
                OrderResponse response = toResponse(updated);
                if (application == PaymentIntegrityService.RefundApplication.REPLAY) metrics.refundReplay();
                else metrics.refundSucceeded();
                return response;
            }
            return refundManual(actorId, orderId, request, context, ledger);
        } catch (BusinessConflictException | ResourceNotFoundException | AccessDeniedException
                | ResponseStatusException ex) {
            metrics.refundRejected();
            throw ex;
        } catch (RuntimeException ex) {
            metrics.refundError();
            log.error("Refund failed");
            throw ex;
        }
    }

    private OrderResponse refundManual(UUID actorId, UUID orderId, PaymentRequest request,
                                       RequestContext context, boolean ledger) {
        if (refundTransactions == null) return refundManualLocked(actorId, orderId, request, context, ledger);
        return refundTransactions.execute(status -> refundManualLocked(actorId, orderId, request, context, ledger));
    }

    private OrderResponse refundManualLocked(UUID actorId, UUID orderId, PaymentRequest request,
                                             RequestContext context, boolean ledger) {
        OrderEntity order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        entityManager.refresh(order);
        if (ledger && refundCases != null && refundCases.existsByOrderIdAndStatusIn(orderId,
                java.util.EnumSet.of(
                        com.utewedding.app.domain.ReturnCaseStatus.REQUESTED,
                        com.utewedding.app.domain.ReturnCaseStatus.UNDER_REVIEW,
                        com.utewedding.app.domain.ReturnCaseStatus.APPROVED,
                        com.utewedding.app.domain.ReturnCaseStatus.AWAITING_RETURN,
                        com.utewedding.app.domain.ReturnCaseStatus.RETURN_IN_TRANSIT,
                        com.utewedding.app.domain.ReturnCaseStatus.RECEIVED,
                        com.utewedding.app.domain.ReturnCaseStatus.INSPECTED,
                        com.utewedding.app.domain.ReturnCaseStatus.RESOLUTION_PENDING,
                        com.utewedding.app.domain.ReturnCaseStatus.REFUND_PENDING,
                        com.utewedding.app.domain.ReturnCaseStatus.REFUND_FAILED,
                        com.utewedding.app.domain.ReturnCaseStatus.EXCHANGE_PENDING,
                        com.utewedding.app.domain.ReturnCaseStatus.DISPUTED,
                        com.utewedding.app.domain.ReturnCaseStatus.RESOLVED,
                        com.utewedding.app.domain.ReturnCaseStatus.CLOSED))) {
            throw new BusinessConflictException("Đơn có hồ sơ trả hàng; hãy hoàn tiền qua resolution của hồ sơ.");
        }
        if (ledger && order.getStatus() == OrderStatus.REFUNDED) {
            metrics.refundReplay();
            return toResponse(order);
        }
        if (ledger && request != null && request.amount() != null && request.amount() < order.getTotalAmount()) {
            throw new BusinessConflictException(
                    "Hoàn tiền một phần phải qua hồ sơ trả hàng để phân bổ đúng sản phẩm và ledger.");
        }
        if (order.getStatus() != OrderStatus.PAID
                && !(!ledger && order.getStatus() == OrderStatus.REFUNDED)) {
            throw new BusinessConflictException("Đơn hàng không ở trạng thái có thể hoàn tiền.");
        }
        settleManualRefund(order, actorId, request, context, ledger);
        metrics.refundSucceeded();
        return toResponse(order);
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.utewedding.app.repository.ReturnCaseRepository refundCases;

    private void settleManualRefund(OrderEntity order, UUID actorId, PaymentRequest request, RequestContext context, boolean ledger) {
        if (request == null || request.providerTransactionId() == null || request.providerTransactionId().isBlank())
            throw new BusinessConflictException("Cần mã giao dịch hoàn tiền thực tế.");
        var prior = paymentRepository.findByProviderTransactionId(request.providerTransactionId().trim()).orElse(null);
        if (prior != null) {
            if (order.getId().equals(prior.getOrderId()) && prior.getStatus() == PaymentStatus.REFUNDED
                    && (request.amount() == null || request.amount() == prior.getAmount())) return;
            throw new BusinessConflictException("Mã hoàn tiền đã được sử dụng.");
        }
        var history = paymentRepository.findByOrderIdOrderByCreatedAtDesc(order.getId());
        long already = history.stream().filter(p -> p.getStatus() == PaymentStatus.REFUNDED && "MANUAL_REFUND".equals(p.getProvider()))
                .mapToLong(PaymentEntity::getAmount).reduce(0, Math::addExact);
        long captured=history.stream().filter(p->p.getStatus()!=null && p.getStatus().isCaptured() && !"MANUAL_REFUND".equals(p.getProvider()))
                .mapToLong(PaymentEntity::getAmount).reduce(0,Math::addExact);
        if(captured<=0)throw new BusinessConflictException("Không có giao dịch đã thanh toán để hoàn tiền.");
        long remaining = Math.subtractExact(Math.min(order.getTotalAmount(),captured), already);
        long amount = request.amount() == null ? remaining : request.amount();
        if (amount <= 0 || amount > remaining) throw new BusinessConflictException("Số tiền hoàn vượt số đã thanh toán còn lại.");
        var payment = new PaymentEntity(); payment.setOrderId(order.getId()); payment.setProvider("MANUAL_REFUND");
        payment.setProviderTransactionId(request.providerTransactionId().trim()); payment.setAmount(amount);
        payment.setCurrency(order.getCurrency()); payment.setStatus(PaymentStatus.REFUNDED); payment.setProcessedAt(Instant.now());
        paymentRepository.save(payment);
        var previousOrder=order.getStatus();
        if (amount == remaining) {
            applyTransition(order, OrderStatus.REFUNDED, Instant.now()); order.setPaymentStatus(PaymentStatus.REFUNDED);
        } else order.setPaymentStatus(PaymentStatus.PARTIALLY_REFUNDED);
        orderRepository.save(order);
        if(previousOrder!=order.getStatus())auditLog.record("ORDER_STATUS_CHANGED",actorId,"Order",order.getId(),Map.of("from",previousOrder.name(),"to",order.getStatus().name()),context);
        auditLog.record("PAYMENT_STATUS_CHANGED", actorId, "Payment", payment.getId(), Map.of("amount", amount, "status", "REFUNDED"), context);
        if (accountingDocuments != null) accountingDocuments.ensure(order,
                com.utewedding.app.domain.AccountingDocumentType.CREDIT_NOTE,
                request.providerTransactionId(), amount);
    }

    private void settle(OrderEntity order, UUID actorId, PaymentRequest request, RequestContext context,
                        PaymentStatus status) {
        String transactionId = request == null ? null : normalize(request.providerTransactionId());
        if (transactionId == null) throw new BusinessConflictException("Mã giao dịch là bắt buộc.");
        if (paymentRepository.findByProviderTransactionId(transactionId).isPresent())
            throw new BusinessConflictException("Giao dịch thanh toán đã được sử dụng.");
        Instant now = Instant.now();
        PaymentEntity payment = new PaymentEntity();
        payment.setOrderId(order.getId());
        payment.setStatus(status);
        payment.setAmount(order.getTotalAmount());
        payment.setCurrency(order.getCurrency());
        payment.setProvider(request.provider() == null || request.provider().isBlank()
                ? (status == PaymentStatus.REFUNDED ? "MANUAL_REFUND" : "MANUAL") : request.provider().trim());
        payment.setProviderTransactionId(transactionId);
        payment.setProcessedAt(now);
        paymentRepository.save(payment);
        OrderStatus previous = order.getStatus();
        PaymentStatus previousPaymentStatus = order.getPaymentStatus();
        OrderStatus target = status == PaymentStatus.REFUNDED ? OrderStatus.REFUNDED : OrderStatus.PAID;
        applyTransition(order, target, now);
        order.setPaymentStatus(status);
        if (status == PaymentStatus.SUCCEEDED) order.setPaidAt(now);
        orderRepository.save(order);
        if (accountingDocuments != null && status == PaymentStatus.SUCCEEDED) {
            accountingDocuments.ensure(order, com.utewedding.app.domain.AccountingDocumentType.PAYMENT_RECEIPT,
                    transactionId);
        } else if (accountingDocuments != null && status == PaymentStatus.REFUNDED) {
            accountingDocuments.ensure(order, com.utewedding.app.domain.AccountingDocumentType.CREDIT_NOTE,
                    transactionId, order.getTotalAmount());
        }
        if (fulfillmentService != null && status == PaymentStatus.SUCCEEDED) {
            fulfillmentService.ensureForOrder(order.getId(), context);
        }
        auditLog.record("ORDER_STATUS_CHANGED", actorId, "Order", order.getId(),
                Map.of("from", previous.name(), "to", order.getStatus().name()), context);
        auditLog.record("PAYMENT_STATUS_CHANGED", actorId, "Payment", payment.getId(),
                Map.of("status", String.valueOf(previousPaymentStatus) + " -> " + status.name()), context);
        if (status == PaymentStatus.SUCCEEDED) metrics.payment(payment.getProvider(), "succeeded");
    }

    private void applyTransition(OrderEntity order, OrderStatus target, Instant at) {
        OrderStatus current = order.getStatus();
        if (current == target) return;
        if (current == null || !current.canTransitionTo(target)) {
            throw new BusinessConflictException("Không thể chuyển đơn hàng từ "
                    + (current == null ? "NULL" : current.name()) + " sang " + target.name() + ".");
        }
        int updated = orderRepository.compareAndSetStatus(order.getId(), current.name(), target.name(), at);
        if (updated != 1) {
            throw new BusinessConflictException("Đơn hàng đã được cập nhật bởi một thao tác khác.");
        }
        order.transitionTo(target);
        if (target == OrderStatus.CANCELLED || target == OrderStatus.EXPIRED) {
            order.setCancelledAt(at);
        }
    }

    private void requireOperationsRole(UUID actorId) {
        if (actorId == null || userRepository == null) throw new AccessDeniedException("Không xác định được người vận hành.");
        com.utewedding.app.domain.entity.UserEntity actor = userRepository.findById(actorId)
                .orElseThrow(() -> new AccessDeniedException("Tài khoản không tồn tại."));
        boolean allowed = actor.getRole() == UserRole.ADMIN || actor.getRole() == UserRole.MANAGER
                || actor.getRole() == UserRole.SUPER_ADMIN;
        if (!allowed)
            throw new AccessDeniedException("Tài khoản không có quyền vận hành đơn hàng.");
    }

    private void cancelOpenFulfillments(UUID orderId, UUID actorId, RequestContext context) {
        if (fulfillmentRepository == null) return;
        List<OrderFulfillmentEntity> rows = fulfillmentRepository.findByOrderIdForUpdateOrderByIdAsc(orderId);
        for (OrderFulfillmentEntity row : rows) {
            entityManager.refresh(row);
            FulfillmentStatus status = row.getStatus();
            if (status != null && status.hasLeftTheWarehouse()) {
                throw new BusinessConflictException("Không thể hủy đơn khi hàng đã rời kho hoặc đã giao.");
            }
        }
        for (OrderFulfillmentEntity row : rows) {
            entityManager.refresh(row);
            FulfillmentStatus status = row.getStatus();
            if (status == null || status == FulfillmentStatus.CANCELLED) continue;
            if (!status.canTransitionTo(FulfillmentStatus.CANCELLED)) {
                throw new BusinessConflictException("Không thể hủy đơn khi hàng đã rời kho hoặc đã giao.");
            }
            int updated = fulfillmentRepository.compareAndSetStatus(row.getId(), status.name(),
                    FulfillmentStatus.CANCELLED.name(), row.getVersion(), Instant.now());
            if (updated != 1) {
                throw new BusinessConflictException("Không thể hủy đơn khi hàng đã rời kho hoặc đã giao.");
            }
            entityManager.detach(row);
            row.setStatus(FulfillmentStatus.CANCELLED);
            auditLog.record("FULFILLMENT_STATUS_CHANGED", actorId, "OrderFulfillment", row.getId(),
                    Map.of("from", status.name(), "to", FulfillmentStatus.CANCELLED.name()), context);
        }
    }

    private void reserveInventory(List<ProductReservation> reservations) {
        for (ProductReservation reservation : reservations) {
            ProductEntity product = reservation.product();
            int quantity = reservation.quantity();
            int remaining = product.getStockQuantity() - quantity;
            UUID productId = product.getId();
            long version = product.getVersion();
            // Detach before the conditional update so a later flush cannot write the pre-update stock
            // back with the old version token.
            entityManager.detach(product);
            int updated = productRepository.decrementStockIfAvailable(productId, quantity, version);
            if (updated != 1) {
                throw new BusinessConflictException("Tồn kho của sản phẩm đã thay đổi, vui lòng cập nhật giỏ hàng.");
            }
            product.setStockQuantity(remaining);
        }
    }

    private void restoreStock(UUID orderId) {
        for (OrderItemEntity item : orderItemRepository.findByOrderIdOrderByIdAsc(orderId).stream()
                .sorted(Comparator.comparing(OrderItemEntity::getProductId)).toList()) {
            ProductEntity product = productRepository.findByIdForUpdateIncludingInactive(item.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm để hoàn tồn kho."));
            int restored = Math.addExact(product.getStockQuantity(), item.getQuantity());
            UUID productId = product.getId();
            long version = product.getVersion();
            entityManager.detach(product);
            int updated = productRepository.incrementStock(productId, item.getQuantity(), version);
            if (updated != 1) {
                throw new BusinessConflictException("Không thể hoàn tồn kho.");
            }
            product.setStockQuantity(restored);
        }
    }

    private OrderResponse replayIfPresent(UUID userId, String key, String requestHash) {
        if (idempotencyRepository == null) return null;
        OrderIdempotencyEntity claim = idempotencyRepository.findById(key).orElse(null);
        if (claim != null) {
            if (!userId.equals(claim.getUserId())) {
                throw new BusinessConflictException("Khoá idempotency đã được sử dụng.");
            }
            if (claim.getRequestHash() == null || !claim.getRequestHash().equals(requestHash)) {
                throw new BusinessConflictException("Khoá idempotency đã được dùng cho một nội dung yêu cầu khác.");
            }
            if (claim.getOrderId() == null) {
                throw new BusinessConflictException("Yêu cầu cùng khoá đang được xử lý.");
            }
            OrderResponse replayed = orderRepository.findByIdAndUserId(claim.getOrderId(), userId)
                    .map(this::toResponse)
                    .orElseThrow(() -> new BusinessConflictException("Không tìm thấy đơn idempotent."));
            metrics.orderReplay();
            return replayed;
        }
        OrderEntity existing = orderRepository.findByIdempotencyKey(key).orElse(null);
        if (existing == null) return null;
        if (!userId.equals(existing.getUserId())) {
            throw new BusinessConflictException("Khoá idempotency đã được sử dụng.");
        }
        throw new BusinessConflictException("Khoá idempotency đã được dùng cho một nội dung yêu cầu khác.");
    }

    private OrderResponse toResponse(OrderEntity order) {
        return toResponse(order, orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()),
                paymentRepository.findByOrderIdOrderByCreatedAtDesc(order.getId()));
    }

    private OrderResponse toResponse(OrderEntity order, List<OrderItemEntity> items, List<PaymentEntity> payments) {
        List<OrderItemResponse> itemResponses = items.stream()
                .map(item -> new OrderItemResponse(item.getProductId(), item.getProductName(),
                        item.getUnitPrice(), item.getQuantity(), item.getDiscountAmount(), item.getLineTotal())).toList();
        List<PaymentResponse> paymentResponses = payments.stream()
                .map(payment -> new PaymentResponse(payment.getId(), payment.getStatus(), payment.getProvider(),
                        payment.getFailureReason(), payment.getProcessedAt(), payment.getCreatedAt())).toList();
        PaymentMethod method = null;
        if (order.getPaymentMethod() != null) {
            try { method = PaymentMethod.valueOf(order.getPaymentMethod()); } catch (IllegalArgumentException ignored) { }
        }
        ShippingAddressResponse shipping = order.getShippingAddress() == null ? null
                : new ShippingAddressResponse(null, null, order.getShippingRecipientName(),
                        order.getShippingPhone(), order.getShippingAddress(), order.getShippingWard(),
                        order.getShippingDistrict(), order.getShippingProvince(),
                        order.getShippingProvinceCode(), order.getShippingProvinceName(),
                        order.getShippingWardCode(), order.getShippingWardName(), false,
                        order.getShippingAddressCatalogSource());
        List<FulfillmentResponse> fulfillments = fulfillmentService == null || order.getId() == null
                ? List.of() : fulfillmentService.listForOrder(order.getId());
        var overall = fulfillmentService == null || order.getId() == null
                ? com.utewedding.app.domain.OverallFulfillmentStatus.NOT_READY
                : fulfillmentService.overallForOrder(order.getId());
        return new OrderResponse(order.getId(), order.getOrderNumber(), order.getStatus(),
                order.getSubtotal(), order.getDiscountAmount(), order.getTotalAmount(), order.getCurrency(),
                order.getWeddingId(), order.getPaidAt(), order.getCancelledAt(), order.getCreatedAt(),
                itemResponses, paymentResponses, method, shipping, order.getShippingFee(), order.getExpiresAt(),
                order.getPaymentStatus(), fulfillments, overall, order.getDiscountCode(), order.getConfirmedAt(),
                order.getShippingFeeBeforeDiscount(), order.getShippingDiscountAmount(),
                order.getVoucherDiscountAmount(), order.getVoucherType());
    }

    private String generateOrderNumber() {
        return "WT" + ORDER_DATE.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
    private String requireIdempotencyKey(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Khoá idempotency là bắt buộc.");
        }
        if (normalized.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Khoá idempotency không được dài quá 120 ký tự.");
        }
        return normalized;
    }
    private String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String blankToNull(String value) { return trimToNull(value); }
    private String orderItemName(String productName, String customizationText) {
        if (customizationText == null) return productName;
        String suffix = " (" + customizationText + ")";
        String base = productName == null ? "Sản phẩm" : productName;
        int maxBaseLength = Math.max(0, 120 - suffix.length());
        if (base.length() > maxBaseLength) base = base.substring(0, maxBaseLength).stripTrailing();
        return base + suffix;
    }
    private String required(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) throw new BusinessConflictException(message);
        return normalized;
    }

    private record ProductReservation(ProductEntity product, int quantity) {
    }

    private record PricingCalculation(CheckoutPricingPreview preview,
                                      List<ProductReservation> reservations,
                                      List<OrderItemEntity> items,
                                      ShippingAddressRequest shipping) {
    }
}
