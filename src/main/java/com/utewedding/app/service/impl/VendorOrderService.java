package com.utewedding.app.service.impl;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.dto.response.VendorOrderSummary;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.security.UserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VendorOrderService {
    private final MarketplaceFeatureFlags flags;
    private final VendorWorkspaceContext workspace;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderFulfillmentRepository fulfillments;

    public VendorOrderService(MarketplaceFeatureFlags flags, VendorWorkspaceContext workspace,
                              OrderRepository orders, OrderItemRepository items, OrderFulfillmentRepository fulfillments) {
        this.flags = flags;
        this.workspace = workspace;
        this.orders = orders;
        this.items = items;
        this.fulfillments = fulfillments;
    }

    @Transactional(readOnly = true)
    public Page<VendorOrderSummary> list(UserPrincipal actor, int page) {
        return orders.findByShopIdOrderByCreatedAtDesc(shop(actor), PageRequest.of(Math.max(0, page), 25));
    }

    @Transactional(readOnly = true)
    public Detail detail(UserPrincipal actor, UUID id) {
        var order = orders.findByIdAndShopId(id, shop(actor))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng trong cửa hàng."));
        // Child rows are reached only after checking the immutable parent shop snapshot.
        return new Detail(order.getId(), order.getOrderNumber(), order.getStatus(), order.getPaymentStatus(),
                order.getPaymentMethod(), order.getConfirmedAt() != null,
                items.findByOrderIdOrderByIdAsc(id).stream().map(item -> new Item(item.getProductName(), item.getQuantity())).toList(),
                fulfillments.findByOrderIdOrderByCreatedAtAsc(id).stream().map(row -> row.getStatus()).toList());
    }

    private UUID shop(UserPrincipal actor) {
        if (!flags.isEnabled(Feature.VENDOR_ORDERS)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return workspace.requireWorkspace(actor).shopId();
    }

    public record Item(String name, int quantity) { }
    public record Detail(UUID id, String orderNumber, com.utewedding.app.domain.OrderStatus status,
                         com.utewedding.app.domain.PaymentStatus paymentStatus, String paymentMethod,
                         boolean confirmed, List<Item> items, List<FulfillmentStatus> fulfillments) { }
}
