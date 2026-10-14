package com.utewedding.app.service;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.dto.request.CreateOrderRequest;
import com.utewedding.app.dto.request.PaymentRequest;
import com.utewedding.app.dto.response.OrderResponse;
import com.utewedding.app.dto.response.PublicOrderTrackingResponse;
import com.utewedding.app.dto.response.CheckoutPricingPreview;
import com.utewedding.app.service.web.OrderHistoryGroup;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderService {
    OrderResponse create(UUID userId, CreateOrderRequest request, RequestContext context);
    CheckoutPricingPreview preview(UUID userId, CreateOrderRequest request);
    OrderResponse get(UUID userId, UUID orderId);
    OrderResponse getByOrderNumber(UUID userId, String orderNumber);
    UUID getTrackingAccessCode(UUID userId, UUID orderId);
    PublicOrderTrackingResponse getPublicTracking(UUID trackingAccessCode);
    List<OrderResponse> list(UUID userId);
    Page<OrderResponse> listPage(UUID userId, Pageable pageable);
    OrderResponse markPaid(UUID userId, UUID orderId, PaymentRequest request,
                           RequestContext context);
    OrderResponse cancel(UUID userId, UUID orderId, RequestContext context);
    OrderResponse cancel(UUID userId, UUID orderId, String reason, RequestContext context);
    int expirePendingOrders(Instant now, RequestContext context);
    OrderResponse refundReturn(UUID actorId, UUID orderId, PaymentRequest request, RequestContext context);
    OrderResponse refund(UUID actorId, UUID orderId, PaymentRequest request,
                         RequestContext context);
    Page<OrderResponse> searchMine(UUID userId, String orderNumber, OrderStatus status,
                                   PaymentStatus paymentStatus, FulfillmentStatus fulfillmentStatus,
                                   Instant from, Instant to, Pageable pageable);
    Page<OrderResponse> searchMine(UUID userId, String orderNumber, OrderStatus status,
                                   PaymentStatus paymentStatus, FulfillmentStatus fulfillmentStatus,
                                   OrderHistoryGroup historyGroup, Instant from, Instant to,
                                   Pageable pageable);
    java.util.Map<OrderHistoryGroup, Long> countMineByGroup(UUID userId);
    OrderResponse getForOperations(UUID actorId, UUID orderId);
    Page<OrderResponse> searchForOperations(UUID actorId, String orderNumber, String buyer,
                                            OrderStatus status, PaymentStatus paymentStatus,
                                            FulfillmentStatus fulfillmentStatus, boolean attentionOnly,
                                            UUID shipperId, Instant from, Instant to, Pageable pageable);
}
