package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ProductReviewReportStatus;
import com.utewedding.app.domain.ProductReviewStatus;
import com.utewedding.app.domain.ReturnCaseStatus;
import com.utewedding.app.domain.CodReconciliationStatus;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.entity.OrderFulfillmentEntity;
import com.utewedding.app.dto.response.OrderResponse;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.CodReconciliationRepository;
import com.utewedding.app.repository.FulfillmentShipmentRepository;
import com.utewedding.app.repository.ProductReviewReportRepository;
import com.utewedding.app.repository.ProductReviewRepository;
import com.utewedding.app.repository.ReturnCaseRepository;
import com.utewedding.app.service.impl.SupportService;
import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared counts used by the dashboard, admin menu, and live attention endpoint. */
@Service
public class AdminAttentionService {
    private static final List<ReturnCaseStatus> ACTIONABLE_RETURNS = List.of(
            ReturnCaseStatus.REQUESTED, ReturnCaseStatus.UNDER_REVIEW, ReturnCaseStatus.RECEIVED,
            ReturnCaseStatus.INSPECTED, ReturnCaseStatus.RESOLUTION_PENDING,
            ReturnCaseStatus.REFUND_FAILED, ReturnCaseStatus.DISPUTED);

    private final OrderRepository orders;
    private final SupportService support;
    private final ProductReviewRepository reviews;
    private final ProductReviewReportRepository reports;
    private final ReturnCaseRepository returns;
    private final OrderFulfillmentRepository fulfillments;
    private final CodReconciliationRepository codReconciliations;
    private final FulfillmentShipmentRepository shipments;

    public AdminAttentionService(OrderRepository orders, SupportService support,
                                 ProductReviewRepository reviews, ProductReviewReportRepository reports,
                                 ReturnCaseRepository returns, OrderFulfillmentRepository fulfillments,
                                 CodReconciliationRepository codReconciliations,
                                 FulfillmentShipmentRepository shipments) {
        this.orders = orders;
        this.support = support;
        this.reviews = reviews;
        this.reports = reports;
        this.returns = returns;
        this.fulfillments = fulfillments;
        this.codReconciliations = codReconciliations;
        this.shipments = shipments;
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot() {
        long orderCount = orders.searchForAdmin(null, null, null, null, true,
                null, null, null, null, PageRequest.of(0, 1)).getTotalElements();
        return new Snapshot(orderCount, support.countNeedsStaffReply(),
                reviews.countByStatus(ProductReviewStatus.PENDING),
                reports.countByStatus(ProductReviewReportStatus.OPEN),
                returns.countByStatusIn(ACTIONABLE_RETURNS), Instant.now());
    }

    @Transactional(readOnly = true)
    public Map<UUID, AttentionItem> orderDetails(Collection<OrderResponse> ordersInPage) {
        if (ordersInPage == null || ordersInPage.isEmpty()) return Map.of();
        List<UUID> orderIds = ordersInPage.stream().map(OrderResponse::id).toList();
        List<OrderFulfillmentEntity> fulfillmentRows = fulfillments.findByOrderIdIn(orderIds,
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
        Map<UUID, List<OrderFulfillmentEntity>> byOrder = new LinkedHashMap<>();
        fulfillmentRows.forEach(row -> byOrder.computeIfAbsent(row.getOrderId(), ignored -> new java.util.ArrayList<>()).add(row));
        List<UUID> fulfillmentIds = fulfillmentRows.stream().map(OrderFulfillmentEntity::getId).toList();
        Map<UUID, String> providerByFulfillment = fulfillmentIds.isEmpty() ? Map.of()
                : shipments.findByFulfillmentIdIn(fulfillmentIds).stream().collect(java.util.stream.Collectors.toMap(
                        shipment -> shipment.getFulfillmentId(), shipment -> shipment.getProviderCode(),
                        (earlier, later) -> later));
        Map<UUID, CodReconciliationStatus> codByOrder = codReconciliations.findByOrderIdIn(orderIds).stream()
                .collect(java.util.stream.Collectors.toMap(row -> row.getOrderId(), row -> row.getStatus()));
        Map<UUID, AttentionItem> result = new LinkedHashMap<>();
        for (OrderResponse order : ordersInPage) {
            List<OrderFulfillmentEntity> rows = byOrder.getOrDefault(order.id(), List.of());
            AttentionItem item = null;
            if (order.paymentMethod() == com.utewedding.app.domain.PaymentMethod.COD
                    && order.status() == OrderStatus.PENDING && order.confirmedAt() == null) {
                item = new AttentionItem("Chờ shop xác nhận", "Xác nhận đơn COD để bắt đầu chuẩn bị hàng");
            } else if (order.status() == OrderStatus.PAYMENT_REVIEW) {
                item = new AttentionItem("Thanh toán cần xác minh", "Kiểm tra giao dịch và cập nhật kết quả");
            } else if (order.paymentStatus() == PaymentStatus.RECONCILIATION_REQUIRED) {
                item = new AttentionItem("Thanh toán cần đối soát", "Đối chiếu số tiền đã nhận");
            } else if (codByOrder.get(order.id()) == CodReconciliationStatus.AWAITING_AMOUNT
                    || codByOrder.get(order.id()) == CodReconciliationStatus.REVIEW_REQUIRED) {
                item = new AttentionItem("Cần đối chiếu tiền COD", "Xác nhận số tiền thu được");
            } else if (order.status() == OrderStatus.PAID && rows.isEmpty()) {
                item = new AttentionItem("Chưa có phiếu chuẩn bị hàng", "Tạo phiếu chuẩn bị cho đơn");
            } else {
                for (OrderFulfillmentEntity fulfillment : rows) {
                    if (fulfillment.getStatus() == FulfillmentStatus.DELIVERY_FAILED) {
                        item = new AttentionItem("Giao hàng thất bại", "Chọn giao lại hoặc xử lý hoàn hàng");
                        break;
                    }
                    if (fulfillment.getStatus() == FulfillmentStatus.CONFIRMED) {
                        item = new AttentionItem("Đơn đã tiếp nhận", "Bắt đầu chuẩn bị hàng");
                        break;
                    }
                    if (fulfillment.getStatus() == FulfillmentStatus.PROCESSING) {
                        item = new AttentionItem("Đơn đang được chuẩn bị", "Hoàn tất chuẩn bị và bàn giao");
                        break;
                    }
                    if (fulfillment.getStatus() == FulfillmentStatus.PICKUP_PENDING
                            && fulfillment.getShipperId() == null
                            && "INTERNAL".equalsIgnoreCase(providerByFulfillment.get(fulfillment.getId()))) {
                        item = new AttentionItem("Đơn nội bộ chưa có nhân viên giao", "Gán nhân viên giao hàng");
                        break;
                    }
                }
            }
            if (item != null) result.put(order.id(), item);
        }
        return Map.copyOf(result);
    }

    public record AttentionItem(String reason, String nextAction) { }

    public record Snapshot(long orders, long tickets, long reviews, long reviewReports,
                           long returns, Instant updatedAt) {
        public long getSales() { return orders + returns; }
        public long getContentReviews() { return reviews + reviewReports; }

        public long count(String key) {
            return switch (key) {
                case "sales" -> getSales();
                case "contentReviews" -> getContentReviews();
                case "orders" -> orders;
                case "tickets" -> tickets;
                case "reviews" -> reviews;
                case "reviewReports" -> reviewReports;
                case "returns" -> returns;
                default -> 0;
            };
        }
    }
}
