package com.utewedding.app.controller;

import com.utewedding.app.domain.AuditAction;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentMethod;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.entity.AuditLogEntity;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.dto.request.CreateOrderRequest;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import com.utewedding.app.dto.response.OrderItemResponse;
import com.utewedding.app.dto.response.OrderResponse;
import com.utewedding.app.dto.response.MultiShopCheckoutResponse;
import com.utewedding.app.dto.response.CheckoutPricingPreview;
import com.utewedding.app.dto.response.CheckoutVoucherPreview;
import com.utewedding.app.integration.VNPayService;
import com.utewedding.app.integration.MoMoService;
import com.utewedding.app.repository.AuditLogRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.service.OrderService;
import com.utewedding.app.service.ShipInfoService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.web.CurrentUserContext;
import com.utewedding.app.service.web.OrderDetailView;
import com.utewedding.app.service.web.OrderHistoryGroup;
import com.utewedding.app.service.web.WebCartService;
import com.utewedding.app.service.web.WebViewModels;
import com.utewedding.app.service.impl.ShippingQuoteService;
import com.utewedding.app.service.impl.AccountingDocumentService;
import com.utewedding.app.service.impl.CheckoutSelectionResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.validation.BindingResult;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import com.utewedding.app.exception.CheckoutPriceChangedException;
import com.utewedding.app.exception.ShippingQuoteExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class OrderWebController {

    private static final Logger log = LoggerFactory.getLogger(OrderWebController.class);

    private final WebCartService cartService;
    private final OrderService orderService;
    private final VNPayService vnPayService;
    private final MoMoService momoService;
    private final CurrentUserContext currentUser;
    private final ShipInfoService shipInfoService;
    private final ProductRepository productRepository;
    private final AuditLogRepository auditLogRepository;
    private OrderRepository orderRepository;
    private ShippingQuoteService shippingQuotes;
    private com.utewedding.app.service.impl.ShopShippingQuoteService shopShippingQuotes;
    private AccountingDocumentService accountingDocuments;
    private com.utewedding.app.service.AdministrativeAddressService addressDirectory;
    private CheckoutSelectionResolver checkoutSelectionResolver;
    private com.utewedding.app.config.MarketplaceFeatureFlags featureFlags;

    @Autowired
    public OrderWebController(WebCartService cartService, OrderService orderService,
                              VNPayService vnPayService, CurrentUserContext currentUser,
                              ShipInfoService shipInfoService, MoMoService momoService,
                              ProductRepository productRepository, AuditLogRepository auditLogRepository) {
        this.cartService = cartService;
        this.orderService = orderService;
        this.vnPayService = vnPayService;
        this.currentUser = currentUser;
        this.shipInfoService = shipInfoService;
        this.momoService = momoService;
        this.productRepository = productRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Autowired(required = false)
    void attachShippingQuotes(ShippingQuoteService service) { this.shippingQuotes = service; }
    @Autowired(required = false)
    void attachShopShippingQuotes(com.utewedding.app.service.impl.ShopShippingQuoteService service) {
        this.shopShippingQuotes = service;
    }
    @Autowired(required = false)
    void attachAddressDirectory(com.utewedding.app.service.AdministrativeAddressService service) {
        this.addressDirectory = service;
    }
    @Autowired(required = false)
    void attachAccountingDocuments(AccountingDocumentService service) { this.accountingDocuments = service; }
    @Autowired(required = false)
    void attachOrderRepository(OrderRepository repository) { this.orderRepository = repository; }
    @Autowired(required = false)
    void attachCheckoutSelectionResolver(CheckoutSelectionResolver resolver) {
        this.checkoutSelectionResolver = resolver;
    }
    @Autowired(required = false)
    void attachFeatureFlags(com.utewedding.app.config.MarketplaceFeatureFlags featureFlags) {
        this.featureFlags = featureFlags;
    }

    @Autowired(required=false) private com.utewedding.app.service.impl.ReturnCaseQueryService returnQueries;

    @GetMapping("/checkout")
    public String checkout(Authentication authentication,
                           @RequestParam(required = false) UUID shopId,
                           @RequestParam(required = false) String selectionFingerprint,
                           @RequestParam(required = false) List<UUID> selectedCartItemIds,
                           Model model) {
        if (groupedCheckoutEnabled()) {
            populateGroupedCheckout(authentication, model, selectedCartItemIds, shopId);
            return "cart/checkout-grouped";
        } else {
            populateCheckout(authentication, model, shopId, selectionFingerprint);
        }
        return "cart/checkout";
    }

    /** Compatibility entry point used by older controller tests and callers. */
    public String checkout(Authentication authentication, UUID shopId,
                           String selectionFingerprint, Model model) {
        return checkout(authentication, shopId, selectionFingerprint, null, model);
    }

    @PostMapping("/checkout/preview")
    @ResponseBody
    public CheckoutVoucherPreview previewVoucher(Authentication authentication,
                                                  @ModelAttribute("checkoutRequest") WebViewModels.CheckoutForm form) {
        UUID userId = currentUser.requireUserId(authentication);
        UUID shippingQuoteId = null;
        Instant shippingQuoteExpiresAt = null;
        CheckoutPricingPreview basePricing;
        try {
            if (form.getSelectedShopId() != null
                    && (com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID.equals(form.getSelectedShopId())
                        ? shippingQuotes == null : shopShippingQuotes == null)) {
                throw new com.utewedding.app.exception.BusinessConflictException(
                        "Chưa thể tính giá theo cửa hàng đã chọn. Vui lòng thử lại sau.");
            }
            if (shippingQuotes != null || shopShippingQuotes != null) {
                ShippingQuoteService.QuoteResponse quote = quote(userId, form);
                shippingQuoteId = quote.id();
                shippingQuoteExpiresAt = quote.expiresAt();
                form.setShippingQuoteId(shippingQuoteId);
            }
            CreateOrderRequest baseRequest = toOrderRequest(form, null, null, null);
            basePricing = orderService.preview(userId, baseRequest);
        } catch (RuntimeException exception) {
            return new CheckoutVoucherPreview(false, WebErrorMessages.forException(exception), null, null, null);
        }
        if ((form.getDiscountCode() == null || form.getDiscountCode().isBlank())
                && (form.getShippingDiscountCode() == null || form.getShippingDiscountCode().isBlank())) {
            return new CheckoutVoucherPreview(true, "", basePricing, shippingQuoteId, shippingQuoteExpiresAt);
        }
        try {
            CheckoutPricingPreview pricing = orderService.preview(userId,
                    toOrderRequest(form, form.getDiscountCode(), form.getShippingDiscountCode(), null));
            String successMessage = pricing.appliedMerchandiseVoucherCode() != null
                    && pricing.appliedShippingVoucherCode() != null
                    ? "Đã áp dụng mã giảm tiền hàng và mã vận chuyển."
                    : pricing.appliedShippingVoucherCode() != null
                    ? pricing.shippingVoucherType() == com.utewedding.app.domain.VoucherType.SHIPPING_FREE
                        ? "Đã áp dụng miễn phí vận chuyển." : "Đã áp dụng giảm phí vận chuyển."
                    : "Mã giảm giá đã được áp dụng.";
            return new CheckoutVoucherPreview(true, successMessage, pricing,
                    shippingQuoteId, shippingQuoteExpiresAt);
        } catch (RuntimeException exception) {
            return new CheckoutVoucherPreview(false, WebErrorMessages.forException(exception), basePricing,
                    shippingQuoteId, shippingQuoteExpiresAt);
        }
    }

    @PostMapping("/checkout/place-order")
    public String placeOrder(Authentication authentication,
                             @Valid @ModelAttribute("checkoutRequest") WebViewModels.CheckoutForm form,
                             BindingResult bindingResult,
                             Model model, RedirectAttributes redirectAttributes,
                             HttpServletRequest request) {
        UUID userId = currentUser.requireUserId(authentication);
        if (bindingResult.hasErrors()) {
            populateCheckout(authentication, model);
            return "cart/checkout";
        }
        if (form.getPaymentMethod() != null && !form.getPaymentMethod().isEnabled()) {
            model.addAttribute("checkoutError", "MoMo đang được cập nhật. Vui lòng chọn COD hoặc VNPAY.");
            populateCheckout(authentication, model);
            return "cart/checkout";
        }
        try {
            if (shippingQuotes != null || shopShippingQuotes != null) {
                if (form.getShippingQuoteId() == null) {
                    form.setShippingQuoteId(quote(userId, form).id());
                }
            }
            RequestContext context = new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                    request.getHeader("X-Request-Id"));
            OrderResponse order;
            try {
                order = orderService.create(userId,
                        toOrderRequest(form, form.getDiscountCode(), form.getExpectedTotalAmount()), context);
            } catch (ShippingQuoteExpiredException exception) {
                if (isVendorShopSelection(form.getSelectedShopId())) {
                    form.setShippingQuoteId(quote(userId, form).id());
                } else {
                    if (shippingQuotes == null) throw exception;
                    shippingQuotes.refreshExpired(userId, form.getShippingQuoteId(), shippingRequest(form));
                }
                order = orderService.create(userId,
                        toOrderRequest(form, form.getDiscountCode(), form.getExpectedTotalAmount()), context);
            }
            if (form.getPaymentMethod() == WebViewModels.PaymentOption.VNPAY) {
                try {
                    String paymentUrl = vnPayService.createPaymentUrl(userId, order.orderNumber(),
                            "Thanh toan don " + order.orderNumber(), request.getRemoteAddr()).paymentUrl();
                    return "redirect:" + paymentUrl;
                } catch (RuntimeException exception) {
                    redirectAttributes.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
                    return "redirect:/orders/" + order.id();
                }
            }
            if (form.getPaymentMethod() == WebViewModels.PaymentOption.MOMO && momoService != null) {
                String paymentUrl = momoService.create(userId, order.id()).endpoint();
                return "redirect:" + paymentUrl;
            }
            return "redirect:/order/success/" + order.id();
        } catch (CheckoutPriceChangedException exception) {
            form.setExpectedTotalAmount(exception.getPricing().totalAmount());
            // Preserve the renewed quote while rebuilding the checkout model;
            // populateCheckout otherwise previews again and can replace the
            // amount that the customer must confirm.
            model.addAttribute("pricing", exception.getPricing());
            populateCheckout(authentication, model);
            model.addAttribute("priceChanged", true);
            return "cart/checkout";
        } catch (RuntimeException exception) {
            log.error("Checkout order submission failed for payment method {}", form.getPaymentMethod(), exception);
            model.addAttribute("checkoutError", WebErrorMessages.forException(exception));
            populateCheckout(authentication, model);
            return "cart/checkout";
        }
    }

    @GetMapping("/order/success/{orderId}")
    public String success(Authentication authentication, @PathVariable UUID orderId, Model model) {
        UUID userId = currentUser.requireUserId(authentication);
        model.addAttribute("order", orderService.get(userId, orderId));
        model.addAttribute("trackingAccessCode", orderService.getTrackingAccessCode(userId, orderId));
        return "cart/success";
    }

    @GetMapping({"/checkout-groups/{groupId}", "/checkout-groups/{groupId}/success"})
    public String checkoutGroupResult(@PathVariable UUID groupId, Model model) {
        model.addAttribute("checkoutGroupId", groupId);
        return "cart/group-result";
    }

    @GetMapping({"/orders", "/orders/history"})
    public String history(Authentication authentication,
                          @RequestParam(defaultValue = "") String q,
                          @RequestParam(required = false) OrderStatus status,
                          @RequestParam(required = false) PaymentStatus paymentStatus,
                          @RequestParam(required = false) FulfillmentStatus fulfillmentStatus,
                          @RequestParam(defaultValue = "ALL") OrderHistoryGroup tab,
                          @RequestParam(required = false) LocalDate from,
                          @RequestParam(required = false) LocalDate to,
                          @RequestParam(defaultValue = "0") int page,
                          Model model) {
        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        UUID userId = currentUser.requireUserId(authentication);
        var orders = orderService.searchMine(userId, q, status,
                paymentStatus, fulfillmentStatus, tab,
                from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(),
                org.springframework.data.domain.PageRequest.of(Math.max(page, 0), 10));
        model.addAttribute("orders", orders);
        model.addAttribute("q", q);
        model.addAttribute("status", status == null ? "" : status.name());
        model.addAttribute("paymentStatus", paymentStatus == null ? "" : paymentStatus.name());
        model.addAttribute("fulfillmentStatus", fulfillmentStatus == null ? "" : fulfillmentStatus.name());
        model.addAttribute("selectedTab", tab);
        model.addAttribute("historyGroups", OrderHistoryGroup.values());
        model.addAttribute("historyCounts", orderService.countMineByGroup(userId));
        model.addAttribute("checkoutGroupsByOrder", checkoutGroupsByOrder(userId, orders.getContent()));
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("orderStatuses", OrderStatus.values());
        model.addAttribute("paymentStatuses", PaymentStatus.values());
        model.addAttribute("fulfillmentStatuses", FulfillmentStatus.values());
        return "orders/history";
    }

    @GetMapping("/orders/{orderId}")
    public String detail(Authentication authentication, @PathVariable UUID orderId, Model model) {
        UUID userId = currentUser.requireUserId(authentication);
        OrderResponse order = orderService.get(userId, orderId);
        Map<UUID, ProductEntity> currentProducts = order.items().isEmpty()
                ? Map.of()
                : productRepository.findAllById(order.items().stream()
                        .map(OrderItemResponse::productId).toList()).stream()
                        .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));
        List<OrderDetailView.Item> items = order.items().stream().map(item -> {
            ProductEntity product = currentProducts.get(item.productId());
            return new OrderDetailView.Item(item.productName(), product == null ? null : product.getSku(),
                    product == null ? null : product.getImageUrl(), item.quantity(), item.unitPrice(),
                    item.discountAmount(), item.lineTotal());
        }).toList();
        List<OrderDetailView.StatusTransition> statusHistory = auditLogRepository
                .findByTargetTypeAndTargetIdAndActionOrderByCreatedAtAsc(
                        "Order", orderId, AuditAction.ORDER_STATUS_CHANGED).stream()
                .map(event -> toStatusTransition(event, "Đơn hàng"))
                .filter(transition -> transition.fromStatus() != null || transition.toStatus() != null)
                .collect(Collectors.toCollection(ArrayList::new));
        List<UUID> fulfillmentIds = order.fulfillments().stream()
                .map(com.utewedding.app.dto.response.FulfillmentResponse::id).toList();
        if (!fulfillmentIds.isEmpty()) {
            statusHistory.addAll(auditLogRepository
                    .findByTargetTypeAndTargetIdInAndActionOrderByCreatedAtAsc(
                            "OrderFulfillment", fulfillmentIds, AuditAction.FULFILLMENT_STATUS_CHANGED).stream()
                    .map(event -> toStatusTransition(event, "Giao hàng"))
                    .filter(transition -> transition.fromStatus() != null || transition.toStatus() != null)
                    .toList());
        }
        statusHistory.sort(Comparator.comparing(OrderDetailView.StatusTransition::changedAt));
        long productDiscountTotal = order.items().stream()
                .mapToLong(OrderItemResponse::discountAmount).sum();

        if(returnQueries!=null){model.addAttribute("returnOptions",returnQueries.eligibility(userId,orderId));model.addAttribute("returnCases",returnQueries.existing(userId,orderId));}
        model.addAttribute("order", order);
        model.addAttribute("checkoutGroup", customerCheckoutGroup(userId, orderId));
        model.addAttribute("paymentExpiresAtVn", order.expiresAt() == null ? null
                : order.expiresAt().atZone(ZoneId.of("Asia/Ho_Chi_Minh")));
        model.addAttribute("trackingAccessCode", orderService.getTrackingAccessCode(userId, orderId));
        model.addAttribute("detail", new OrderDetailView(items, statusHistory, productDiscountTotal));
        boolean shippingStarted = order.fulfillments().stream()
                .anyMatch(row -> row.status() != null && row.status().hasLeftTheWarehouse());
        boolean pending = order.status() == OrderStatus.PENDING;
        model.addAttribute("canCancelOrder", pending && !shippingStarted);
        boolean online = order.paymentMethod() == PaymentMethod.VNPAY || order.paymentMethod() == PaymentMethod.MOMO;
        boolean paymentWindowOpen = order.paymentMethod() == PaymentMethod.VNPAY
                ? vnPayService.paymentWindowOpen(order.expiresAt())
                : order.paymentMethod() == PaymentMethod.MOMO && momoService != null
                        && momoService.paymentWindowOpen(order.expiresAt());
        model.addAttribute("canRepay", pending && online && paymentWindowOpen);
        return "orders/detail";
    }

    @PostMapping("/orders/{orderId}/cancel")
    public String cancel(Authentication authentication, @PathVariable UUID orderId,
                         @RequestParam(required = false) String reason,
                         HttpServletRequest request, RedirectAttributes redirectAttributes) {
        try {
            orderService.cancel(currentUser.requireUserId(authentication), orderId, reason,
                    new RequestContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
                            request.getHeader("X-Request-Id")));
            redirectAttributes.addFlashAttribute("successMessage", "Đã hủy đơn hàng.");
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/orders/" + orderId;
    }

    @PostMapping("/orders/{orderId}/pay")
    public String repay(Authentication authentication, @PathVariable UUID orderId, HttpServletRequest request,
                        RedirectAttributes redirectAttributes) {
        try {
            UUID userId = currentUser.requireUserId(authentication);
            var order = orderService.get(userId, orderId);
            if (order.paymentMethod() == PaymentMethod.VNPAY) {
                return "redirect:" + vnPayService.createPaymentUrl(userId,
                        order.orderNumber(), "Thanh toan don " + order.orderNumber(), request.getRemoteAddr()).paymentUrl();
            }
            if (order.paymentMethod() == PaymentMethod.MOMO && momoService != null) {
                return "redirect:" + momoService.create(userId, order.id()).endpoint();
            }
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", WebErrorMessages.forException(exception));
        }
        return "redirect:/orders/" + orderId;
    }

    @GetMapping("/orders/{orderId}/invoice")
    public String invoice(Authentication authentication, @PathVariable UUID orderId, Model model) {
        var order = orderService.get(currentUser.requireUserId(authentication), orderId);
        model.addAttribute("order", order);
        model.addAttribute("productDiscountTotal", order.items().stream()
                .mapToLong(OrderItemResponse::discountAmount).sum());
        if (accountingDocuments != null) {
            model.addAttribute("accountingDocument", accountingDocuments.invoice(orderId,
                    order.paymentStatus() == PaymentStatus.SUCCEEDED
                            || order.paymentStatus() == PaymentStatus.PARTIALLY_REFUNDED
                            || order.paymentStatus() == PaymentStatus.REFUNDED));
        }
        return "orders/invoice";
    }

    private void populateCheckout(Authentication authentication, Model model) {
        populateCheckout(authentication, model, null, null);
    }

    private void populateGroupedCheckout(Authentication authentication, Model model,
                                         List<UUID> requestedCartItemIds, UUID selectedShopId) {
        UUID userId = currentUser.requireUserId(authentication);
        WebViewModels.CartView currentCart = cartService.get(userId);
        List<com.utewedding.app.dto.response.ShippingAddressResponse> addresses =
                shipInfoService == null ? List.of() : shipInfoService.list(userId);
        WebViewModels.CheckoutForm form;
        if (model.containsAttribute("checkoutRequest")) {
            form = (WebViewModels.CheckoutForm) model.getAttribute("checkoutRequest");
        } else {
            form = new WebViewModels.CheckoutForm();
            addresses.stream().filter(com.utewedding.app.dto.response.ShippingAddressResponse::defaultAddress)
                    .findFirst().ifPresent(address -> applySavedAddress(form, address));
        }
        if (form.getIdempotencyKey() == null || form.getIdempotencyKey().isBlank()) {
            form.setIdempotencyKey(UUID.randomUUID().toString());
        }

        java.util.Set<UUID> availableIds = currentCart.getItems().stream()
                .filter(WebViewModels.CartItemView::isCheckoutAvailable)
                .filter(item -> selectedShopId == null || selectedShopId.equals(item.getShopId()))
                .map(WebViewModels.CartItemView::getId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        java.util.LinkedHashSet<UUID> selectedIds = new java.util.LinkedHashSet<>();
        if (requestedCartItemIds == null || requestedCartItemIds.isEmpty()) {
            selectedIds.addAll(availableIds);
        } else {
            requestedCartItemIds.stream().filter(availableIds::contains).forEach(selectedIds::add);
        }

        model.addAttribute("cart", currentCart);
        model.addAttribute("checkoutRequest", form);
        model.addAttribute("selectedCartItemIds", selectedIds);
        model.addAttribute("groupedCheckoutEnabled", true);
        model.addAttribute("paymentMethods", List.of(WebViewModels.PaymentOption.values()));
        model.addAttribute("savedAddresses", addresses);
        String activeCatalogSource = addressDirectory == null ? "LOCAL" : addressDirectory.activeCatalogSource();
        model.addAttribute("activeAddressCatalogSource", activeCatalogSource);
        model.addAttribute("hasLegacySavedAddress", addresses.stream().anyMatch(address ->
                java.util.Objects.equals(address.id(), form.getShippingAddressId())
                        && (!java.util.Objects.equals(address.catalogSource(), activeCatalogSource)
                        || address.provinceCode() == null || address.provinceCode().isBlank()
                        || address.wardCode() == null || address.wardCode().isBlank())));
        if (selectedIds.isEmpty()) {
            model.addAttribute("checkoutError", "Chọn ít nhất một sản phẩm còn khả dụng trong giỏ hàng.");
        }
    }

    private void populateCheckout(Authentication authentication, Model model,
                                  UUID selectedShopId, String suppliedFingerprint) {
        UUID userId = currentUser.requireUserId(authentication);
        WebViewModels.CartView currentCart = cartService.get(userId);
        List<com.utewedding.app.dto.response.ShippingAddressResponse> addresses =
                shipInfoService == null ? List.of() : shipInfoService.list(userId);
        if (!model.containsAttribute("checkoutRequest")) {
            WebViewModels.CheckoutForm form = new WebViewModels.CheckoutForm();
            addresses.stream().filter(com.utewedding.app.dto.response.ShippingAddressResponse::defaultAddress)
                    .findFirst().ifPresent(address -> applySavedAddress(form, address));
            model.addAttribute("checkoutRequest", form);
        }
        WebViewModels.CheckoutForm form = (WebViewModels.CheckoutForm) model.getAttribute("checkoutRequest");
        UUID previousShopId = form.getSelectedShopId();
        String previousFingerprint = form.getSelectionFingerprint();
        if (selectedShopId != null) {
            form.setSelectedShopId(selectedShopId);
            if (suppliedFingerprint == null) {
                currentCart.getShopGroups().stream()
                        .filter(group -> selectedShopId.equals(group.getShopId()))
                        .findFirst().ifPresent(group -> form.setSelectionFingerprint(group.getSelectionFingerprint()));
            }
        }
        if (suppliedFingerprint != null) form.setSelectionFingerprint(suppliedFingerprint);
        if (!java.util.Objects.equals(previousShopId, form.getSelectedShopId())
                || !java.util.Objects.equals(previousFingerprint, form.getSelectionFingerprint())) {
            form.setShippingQuoteId(null);
        }
        boolean selectionRequested = form.getSelectedShopId() != null || hasSelectionFingerprint(form);
        if (selectionRequested) {
            try {
                if (checkoutSelectionResolver == null) {
                    throw new com.utewedding.app.exception.BusinessConflictException(
                            "Không thể xác minh nhóm sản phẩm. Vui lòng quay lại giỏ hàng.");
                }
                CheckoutSelectionResolver.Selection selection = checkoutSelectionResolver.resolve(currentCart,
                        form.getSelectedShopId(), form.getSelectionFingerprint());
                WebViewModels.ShopGroupView selectedGroup = currentCart.getShopGroups().stream()
                        .filter(group -> selection.shopId().equals(group.getShopId()))
                        .findFirst().orElseThrow();
                form.setSelectedShopId(selection.shopId());
                form.setSelectionFingerprint(selection.fingerprint());
                model.addAttribute("cart", new WebViewModels.CartView(currentCart.getId(),
                        selectedGroup.getItems(), List.of(selectedGroup), selectedGroup.getSubtotal(), 0,
                        selectedGroup.getDiscountAmount(), 0, selectedGroup.getTotalAmount(),
                        currentCart.isMultiShopCheckoutEnabled()));
                model.addAttribute("selectedShopGroup", selectedGroup);
                model.addAttribute("selectionCheckoutPending", false);
                model.addAttribute("selectionCheckoutMessage",
                        "Đã chọn sản phẩm của " + selectedGroup.getShopName()
                                + ".");
            } catch (com.utewedding.app.exception.BusinessConflictException exception) {
                model.addAttribute("cart", currentCart);
                model.addAttribute("selectionCheckoutInvalid", true);
                model.addAttribute("checkoutError", WebErrorMessages.forException(exception));
            }
        } else {
            model.addAttribute("cart", currentCart);
            if (currentCart.getShopGroups().size() > 1) {
                model.addAttribute("checkoutSelectionRequired", true);
                model.addAttribute("checkoutSelectionMessage",
                        "Giỏ hàng có nhiều cửa hàng. Hãy chọn cửa hàng muốn thanh toán từ trang giỏ hàng.");
            }
        }
        if (form.getIdempotencyKey() == null || form.getIdempotencyKey().isBlank()) {
            form.setIdempotencyKey(UUID.randomUUID().toString());
        }
        model.addAttribute("checkoutForm", form);
        model.addAttribute("paymentMethods", List.of(WebViewModels.PaymentOption.values()));
        model.addAttribute("savedAddresses", addresses);
        String activeCatalogSource = addressDirectory == null ? "LOCAL" : addressDirectory.activeCatalogSource();
        model.addAttribute("activeAddressCatalogSource", activeCatalogSource);
        model.addAttribute("hasLegacySavedAddress", addresses.stream().anyMatch(address ->
                java.util.Objects.equals(address.id(), form.getShippingAddressId())
                        && (!java.util.Objects.equals(address.catalogSource(), activeCatalogSource)
                        || address.provinceCode() == null || address.provinceCode().isBlank()
                        || address.wardCode() == null || address.wardCode().isBlank())));
        if ((selectionRequested && !model.containsAttribute("selectionCheckoutInvalid")
                || !selectionRequested && currentCart.getShopGroups().size() <= 1)
                && !model.containsAttribute("pricing")) {
            boolean quoteReady = false;
            form.setShippingQuoteId(null);
            try {
                form.setShippingQuoteId(quote(userId, form).id());
                quoteReady = true;
            } catch (RuntimeException exception) {
                model.addAttribute("pricingError", WebErrorMessages.forException(exception));
            }
            if (quoteReady) {
                try {
                    CheckoutPricingPreview pricing = orderService.preview(userId, toOrderRequest(form,
                            form.getDiscountCode(), null));
                    form.setExpectedTotalAmount(pricing.totalAmount());
                    model.addAttribute("pricing", pricing);
                } catch (RuntimeException exception) {
                    if ((form.getDiscountCode() != null && !form.getDiscountCode().isBlank())
                            || (form.getShippingDiscountCode() != null && !form.getShippingDiscountCode().isBlank())) {
                        model.addAttribute("voucherError", WebErrorMessages.forException(exception));
                        form.setDiscountCode(null);
                        form.setShippingDiscountCode(null);
                        try {
                            CheckoutPricingPreview pricing = orderService.preview(userId,
                                    toOrderRequest(form, null, null, null));
                            form.setExpectedTotalAmount(pricing.totalAmount());
                            model.addAttribute("pricing", pricing);
                        } catch (RuntimeException pricingException) {
                            model.addAttribute("pricingError", WebErrorMessages.forException(pricingException));
                        }
                    } else {
                        model.addAttribute("pricingError", WebErrorMessages.forException(exception));
                    }
                }
            }
        } else if (!selectionRequested && currentCart.getShopGroups().size() > 1) {
            model.addAttribute("selectionCheckoutPending", false);
        }
    }

    private boolean hasSelectionFingerprint(WebViewModels.CheckoutForm form) {
        return form.getSelectionFingerprint() != null && !form.getSelectionFingerprint().isBlank();
    }

    private Map<UUID, MultiShopCheckoutResponse> checkoutGroupsByOrder(UUID userId,
                                                                        List<OrderResponse> pageOrders) {
        if (orderRepository == null || pageOrders == null || pageOrders.isEmpty()) return Map.of();
        Map<UUID, MultiShopCheckoutResponse> result = new java.util.LinkedHashMap<>();
        for (OrderResponse order : pageOrders) {
            MultiShopCheckoutResponse group = customerCheckoutGroup(userId, order.id());
            if (group != null) result.put(order.id(), group);
        }
        return Map.copyOf(result);
    }

    /**
     * Resolves group context only after the current child is confirmed as this customer's order.
     * The service performs a second owner check for the parent group; each child is then
     * rechecked against the same customer and group before it can become a customer link.
     */
    MultiShopCheckoutResponse customerCheckoutGroup(UUID userId, UUID orderId) {
        if (orderRepository == null || userId == null || orderId == null) return null;
        UUID groupId = orderRepository.findByIdAndUserId(orderId, userId)
                .map(OrderEntity::getCheckoutGroupId).orElse(null);
        if (groupId == null) return null;
        try {
            MultiShopCheckoutResponse group = orderService.getCheckoutGroup(userId, groupId);
            List<MultiShopCheckoutResponse.ChildOrder> ownChildren = group.orders().stream()
                    .filter(child -> orderRepository.findByIdAndUserId(child.orderId(), userId)
                            .filter(childEntity -> groupId.equals(childEntity.getCheckoutGroupId())).isPresent())
                    .toList();
            boolean currentOrderIncluded = ownChildren.stream()
                    .anyMatch(child -> orderId.equals(child.orderId()));
            if (!currentOrderIncluded || ownChildren.isEmpty()) return null;
            return new MultiShopCheckoutResponse(group.groupId(), group.groupNumber(), group.totalAmount(),
                    group.currency(), group.paymentMethod(), group.expiresAt(), group.paymentStatus(),
                    group.fulfillmentSummary(), ownChildren);
        } catch (org.springframework.security.access.AccessDeniedException
                 | com.utewedding.app.exception.ResourceNotFoundException ignored) {
            // Broken or mismatched group references must not block the customer's single-order view.
            return null;
        }
    }

    private boolean groupedCheckoutEnabled() {
        return featureFlags != null && featureFlags.isEnabled(
                com.utewedding.app.config.MarketplaceFeatureFlags.Feature.GROUPED_CHECKOUT);
    }

    private CreateOrderRequest toOrderRequest(WebViewModels.CheckoutForm form,
                                             String discountCode, Long expectedTotalAmount) {
        return toOrderRequest(form, discountCode, form.getShippingDiscountCode(), expectedTotalAmount);
    }

    private CreateOrderRequest toOrderRequest(WebViewModels.CheckoutForm form,
                                              String discountCode, String shippingDiscountCode,
                                              Long expectedTotalAmount) {
        return new CreateOrderRequest(form.getWeddingId(), form.getIdempotencyKey(), discountCode,
                form.getPaymentMethod() == null ? com.utewedding.app.domain.PaymentMethod.COD
                        : form.getPaymentMethod().toDomain(),
                new ShippingAddressRequest(form.getFullName(), form.getPhone(), form.getShippingAddress(),
                        form.getProvinceCode(), form.getWardCode()),
                form.getShippingAddressId(), expectedTotalAmount, form.getShippingQuoteId(), shippingDiscountCode,
                form.getSelectedShopId(), form.getSelectionFingerprint());
    }

    private ShippingQuoteService.QuoteResponse quote(UUID userId, WebViewModels.CheckoutForm form) {
        if (form.getSelectedShopId() != null) {
            if (com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID.equals(form.getSelectedShopId())) {
                if (shippingQuotes == null) {
                    throw new com.utewedding.app.exception.BusinessConflictException("Chưa thể lấy báo giá giao hàng.");
                }
                return shippingQuotes.quotePlatformSelection(userId, form.getSelectionFingerprint(), shippingRequest(form));
            }
            if (shopShippingQuotes == null) {
                throw new com.utewedding.app.exception.BusinessConflictException(
                        "Không thể lấy báo giá giao hàng của cửa hàng đã chọn.");
            }
            return shopShippingQuotes.quote(userId, new com.utewedding.app.dto.request.ShopShippingQuoteRequest(
                    form.getSelectedShopId(), form.getSelectionFingerprint(), shippingRequest(form)));
        }
        if (shippingQuotes == null) {
            throw new com.utewedding.app.exception.BusinessConflictException(
                    "Chưa thể lấy báo giá giao hàng. Vui lòng thử lại sau.");
        }
        return shippingQuotes.quote(userId, shippingRequest(form));
    }

    private boolean isVendorShopSelection(UUID shopId) {
        return shopId != null && !com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID.equals(shopId);
    }

    private ShippingAddressRequest shippingRequest(WebViewModels.CheckoutForm form) {
        return new ShippingAddressRequest(form.getFullName(), form.getPhone(), form.getShippingAddress(),
                form.getProvinceCode(), form.getWardCode());
    }

    private void applySavedAddress(WebViewModels.CheckoutForm form,
                                   com.utewedding.app.dto.response.ShippingAddressResponse address) {
        form.setShippingAddressId(address.id());
        form.setFullName(address.recipientName());
        form.setPhone(address.phone());
        form.setShippingAddress(address.addressLine());
        form.setProvinceCode(address.provinceCode());
        form.setWardCode(address.wardCode());
    }

    private OrderDetailView.StatusTransition toStatusTransition(AuditLogEntity event, String subject) {
        Map<String, Object> metadata = event.getMetadata();
        return new OrderDetailView.StatusTransition(subject, metadataValue(metadata, "from"),
                metadataValue(metadata, "to"), event.getCreatedAt());
    }

    private String metadataValue(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? null : value.toString();
    }
}
