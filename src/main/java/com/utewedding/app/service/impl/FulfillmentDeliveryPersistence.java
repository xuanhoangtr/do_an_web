package com.utewedding.app.service.impl;

import com.utewedding.app.domain.DeliveryAttemptOutcome;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentMethod;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.entity.DeliveryAttemptEntity;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderFulfillmentEntity;
import com.utewedding.app.domain.entity.OrderFulfillmentItemEntity;
import com.utewedding.app.domain.entity.OrderItemEntity;
import com.utewedding.app.domain.entity.PaymentEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.event.FulfillmentChangedEvent;
import com.utewedding.app.integration.CloudinaryUploadResult;
import com.utewedding.app.repository.DeliveryAttemptRepository;
import com.utewedding.app.repository.OrderFulfillmentItemRepository;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.PaymentRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Locks and validates the delivery before upload, then rechecks before persistence. */
@Service
public class FulfillmentDeliveryPersistence {

    private final OrderFulfillmentRepository fulfillmentRepository;
    private final OrderRepository orderRepository;
    private final AuditLogPort auditLog;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentRepository paymentRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final OrderFulfillmentItemRepository fulfillmentItemRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private CodReconciliationService codReconciliation;
    private ShopFinancialService shopFinancialService;

    public FulfillmentDeliveryPersistence(OrderFulfillmentRepository fulfillmentRepository,
                                          OrderRepository orderRepository,
                                          AuditLogPort auditLog,
                                          ApplicationEventPublisher eventPublisher) {
        this(fulfillmentRepository, orderRepository, auditLog, eventPublisher,
                null, null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FulfillmentDeliveryPersistence(OrderFulfillmentRepository fulfillmentRepository,
                                          OrderRepository orderRepository,
                                          AuditLogPort auditLog,
                                          ApplicationEventPublisher eventPublisher,
                                          PaymentRepository paymentRepository,
                                          DeliveryAttemptRepository attemptRepository,
                                          OrderFulfillmentItemRepository fulfillmentItemRepository,
                                          OrderItemRepository orderItemRepository,
                                          ProductRepository productRepository) {
        this.fulfillmentRepository = fulfillmentRepository;
        this.orderRepository = orderRepository;
        this.auditLog = auditLog;
        this.eventPublisher = eventPublisher;
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.fulfillmentItemRepository = fulfillmentItemRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
    }

    @Transactional(rollbackFor = Exception.class)
    public void validateDelivery(UUID shipperId, UUID fulfillmentId) {
        OrderFulfillmentEntity located = fulfillmentRepository.findById(fulfillmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        if (!Objects.equals(shipperId, located.getShipperId())) {
            throw new AccessDeniedException("Fulfillment này chưa được gán cho bạn.");
        }
        OrderEntity order = orderRepository.findByIdForUpdate(located.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        OrderFulfillmentEntity fulfillment = fulfillmentRepository.findByIdForUpdate(fulfillmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        requireDeliveryOwnershipAndStatus(shipperId, fulfillment);
        requireOrderCanBeDelivered(order);
    }

    @Transactional(rollbackFor = Exception.class)
    public DeliveryResult persist(UUID shipperId, UUID fulfillmentId,
                                  CloudinaryUploadResult upload, RequestContext context) {
        return persist(shipperId, fulfillmentId, upload, null, null, context);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachCodReconciliation(CodReconciliationService service) { this.codReconciliation = service; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachShopFinancialService(ShopFinancialService service) { this.shopFinancialService = service; }

    @Transactional(readOnly = true)
    public DeliveryResult deliveredReplay(UUID shipperId, UUID fulfillmentId, Long collectedAmount,
                                          String collectionReference) {
        OrderFulfillmentEntity fulfillment = fulfillmentRepository.findById(fulfillmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        if (!Objects.equals(shipperId, fulfillment.getShipperId())) {
            throw new AccessDeniedException("Fulfillment này chưa được gán cho bạn.");
        }
        if (fulfillment.getStatus() != FulfillmentStatus.DELIVERED) return null;
        OrderEntity order = orderRepository.findById(fulfillment.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        if (PaymentMethod.COD.name().equalsIgnoreCase(order.getPaymentMethod())) {
            if (codReconciliation == null) {
                throw new BusinessConflictException("Không thể xác minh biên nhận COD của kiện hàng.");
            }
            codReconciliation.validateDeliveredReplay(fulfillmentId, collectedAmount, collectionReference);
        } else if (collectedAmount != null || (collectionReference != null && !collectionReference.isBlank())) {
            throw new BusinessConflictException("Đơn này không thu COD.");
        }
        return new DeliveryResult(fulfillment, order);
    }

    @Transactional(rollbackFor = Exception.class)
    public DeliveryResult persist(UUID shipperId, UUID fulfillmentId,
                                  CloudinaryUploadResult upload, Long collectedAmount,
                                  String collectionReference, RequestContext context) {
        OrderFulfillmentEntity located = fulfillmentRepository.findById(fulfillmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        if (!Objects.equals(shipperId, located.getShipperId())) {
            throw new AccessDeniedException("Fulfillment này chưa được gán cho bạn.");
        }
        OrderEntity order = orderRepository.findByIdForUpdate(located.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        List<OrderFulfillmentEntity> rows = fulfillmentRepository.findByOrderIdForUpdateOrderByIdAsc(order.getId());
        OrderFulfillmentEntity fulfillment = rows.stream()
                .filter(row -> fulfillmentId.equals(row.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        requireDeliveryOwnershipAndStatus(shipperId, fulfillment);
        requireOrderCanBeDelivered(order);
        boolean codOrder = PaymentMethod.COD.name().equalsIgnoreCase(order.getPaymentMethod());
        if (codOrder && collectedAmount == null) {
            throw new BusinessConflictException("Vui lòng nhập số tiền COD thực thu.");
        }
        if (collectedAmount != null && collectedAmount < 0) {
            throw new BusinessConflictException("Số tiền COD thực thu không hợp lệ.");
        }
        if (!codOrder && (collectedAmount != null
                || (collectionReference != null && !collectionReference.isBlank()))) {
            throw new BusinessConflictException("Đơn này không thu COD.");
        }
        boolean completesDelivery = !rows.isEmpty() && rows.stream().allMatch(row ->
                fulfillmentId.equals(row.getId()) || row.getStatus() == FulfillmentStatus.DELIVERED);
        PaymentEntity codPayment = codReconciliation == null
                ? prepareCodPaymentIfFinal(order, completesDelivery) : null;

        FulfillmentStatus previousStatus = fulfillment.getStatus();
        Instant deliveredAt = Instant.now();
        fulfillment.setStatus(FulfillmentStatus.DELIVERED);
        fulfillment.setDeliveredAt(deliveredAt);
        fulfillment.setDeliveryProofUrl(upload.secureUrl());
        fulfillment.setDeliveryProofPublicId(upload.publicId());
        fulfillment = fulfillmentRepository.saveAndFlush(fulfillment);

        auditLog.record("FULFILLMENT_DELIVERED", shipperId, "OrderFulfillment", fulfillmentId,
                Map.of("orderId", order.getId()), context);
        auditLog.record("FULFILLMENT_STATUS_CHANGED", shipperId, "OrderFulfillment", fulfillmentId,
                Map.of("from", previousStatus.name(), "to", FulfillmentStatus.DELIVERED.name()), context);
        eventPublisher.publishEvent(new FulfillmentChangedEvent(
                FulfillmentChangedEvent.Type.DELIVERED, fulfillment.getId(), order.getId(),
                order.getUserId(), fulfillment.getShipperId(),
                fulfillment.getStatus()));
        recordAttempt(fulfillment, shipperId, DeliveryAttemptOutcome.DELIVERED, null, null, null,
                upload.secureUrl(), upload.publicId());
        if (codReconciliation != null && PaymentMethod.COD.name().equalsIgnoreCase(order.getPaymentMethod())) {
            codReconciliation.recordDelivered(order.getId(), fulfillment.getId(), collectedAmount,
                    collectionReference, context);
        }
        if (codPayment != null) applyCodSettlement(order, codPayment, deliveredAt);
        return new DeliveryResult(fulfillment, order);
    }

    @Transactional(rollbackFor = Exception.class)
    public void validateFailure(UUID shipperId, UUID fulfillmentId) {
        LockedPair locked = lockOrderThenFulfillment(fulfillmentId);
        requireAssigned(shipperId, locked.fulfillment());
        if (locked.fulfillment().getStatus() != FulfillmentStatus.IN_TRANSIT) {
            throw new BusinessConflictException("Chỉ đơn đang giao mới có thể ghi nhận giao thất bại.");
        }
        requireOrderCanBeDelivered(locked.order());
    }

    @Transactional(rollbackFor = Exception.class)
    public DeliveryResult fail(UUID shipperId, UUID fulfillmentId, String reasonCode, String note,
                               Instant nextAttemptAt, String proofUrl, String proofPublicId,
                               RequestContext context) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new BusinessConflictException("Lý do giao thất bại là bắt buộc.");
        }
        LockedPair locked = lockOrderThenFulfillment(fulfillmentId);
        OrderFulfillmentEntity fulfillment = locked.fulfillment();
        OrderEntity order = locked.order();
        requireAssigned(shipperId, fulfillment);
        if (fulfillment.getStatus() != FulfillmentStatus.IN_TRANSIT) {
            throw new BusinessConflictException("Chỉ đơn đang giao mới có thể ghi nhận giao thất bại.");
        }
        requireOrderCanBeDelivered(order);
        FulfillmentStatus previous = fulfillment.getStatus();
        boolean customerRefused = "CUSTOMER_REFUSED".equals(reasonCode);
        fulfillment.setStatus(customerRefused ? FulfillmentStatus.RETURNING : FulfillmentStatus.DELIVERY_FAILED);
        if (proofUrl != null) {
            fulfillment.setDeliveryProofUrl(proofUrl);
            fulfillment.setDeliveryProofPublicId(proofPublicId);
        }
        fulfillment = fulfillmentRepository.saveAndFlush(fulfillment);
        auditStatus(shipperId, fulfillmentId, previous, fulfillment.getStatus(), context);
        recordAttempt(fulfillment, shipperId, DeliveryAttemptOutcome.FAILED, reasonCode, note,
                customerRefused ? null : nextAttemptAt, proofUrl, proofPublicId);
        if (customerRefused) {
            recordAttempt(fulfillment, shipperId, DeliveryAttemptOutcome.RETURN_STARTED, reasonCode, note,
                    null, proofUrl, proofPublicId);
        }
        publishStatus(fulfillment, order);
        return new DeliveryResult(fulfillment, order);
    }

    @Transactional(rollbackFor = Exception.class)
    public void validateReturn(UUID shipperId, UUID fulfillmentId) {
        LockedPair locked = lockOrderThenFulfillment(fulfillmentId);
        requireAssigned(shipperId, locked.fulfillment());
        if (locked.fulfillment().getStatus() != FulfillmentStatus.RETURNING) {
            throw new BusinessConflictException("Chỉ đơn đang hoàn mới có thể xác nhận đã hoàn hàng.");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public DeliveryResult completeReturn(UUID shipperId, UUID fulfillmentId, CloudinaryUploadResult upload,
                                         String note, RequestContext context) {
        LockedPair locked = lockOrderThenFulfillment(fulfillmentId);
        OrderFulfillmentEntity fulfillment = locked.fulfillment();
        OrderEntity order = locked.order();
        requireAssigned(shipperId, fulfillment);
        if (fulfillment.getStatus() != FulfillmentStatus.RETURNING) {
            throw new BusinessConflictException("Chỉ đơn đang hoàn mới có thể xác nhận đã hoàn hàng.");
        }
        FulfillmentStatus previous = fulfillment.getStatus();
        fulfillment.setStatus(FulfillmentStatus.RETURNED);
        fulfillment.setReturnedAt(Instant.now());
        fulfillment.setDeliveryProofUrl(upload.secureUrl());
        fulfillment.setDeliveryProofPublicId(upload.publicId());
        fulfillment = fulfillmentRepository.saveAndFlush(fulfillment);
        restoreInventory(fulfillment);
        auditStatus(shipperId, fulfillmentId, previous, fulfillment.getStatus(), context);
        recordAttempt(fulfillment, shipperId, DeliveryAttemptOutcome.RETURNED, null, note, null,
                upload.secureUrl(), upload.publicId());
        if (codReconciliation != null && PaymentMethod.COD.name().equalsIgnoreCase(order.getPaymentMethod())) {
            codReconciliation.recordReturned(order.getId(), fulfillment.getId(), context);
        }
        publishStatus(fulfillment, order);
        return new DeliveryResult(fulfillment, order);
    }

    private PaymentEntity prepareCodPaymentIfFinal(OrderEntity order, boolean completesDelivery) {
        if (!completesDelivery || !PaymentMethod.COD.name().equalsIgnoreCase(order.getPaymentMethod())) {
            return null;
        }
        if (order.getStatus() == OrderStatus.PAID && order.getPaymentStatus() == PaymentStatus.SUCCEEDED) {
            if (order.getDeliveredAt() == null) order.setDeliveredAt(Instant.now());
            orderRepository.save(order);
            return null;
        }
        if (order.getStatus() != OrderStatus.PENDING || order.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new BusinessConflictException("Không thể xác nhận COD vì bản ghi thanh toán bị thiếu hoặc mâu thuẫn.");
        }
        return requirePendingCodPayment(order);
    }

    private PaymentEntity requirePendingCodPayment(OrderEntity order) {
        if (paymentRepository == null) {
            throw new BusinessConflictException("Không thể xác nhận COD vì bản ghi thanh toán bị thiếu hoặc mâu thuẫn.");
        }
        List<PaymentEntity> payments = paymentRepository.findByOrderIdForUpdateOrderByIdAsc(order.getId());
        List<PaymentEntity> codPayments = payments.stream()
                .filter(payment -> PaymentMethod.COD.name().equalsIgnoreCase(payment.getProvider()))
                .toList();
        if (codPayments.size() != 1 || payments.size() != 1) {
            throw new BusinessConflictException("Không thể xác nhận COD vì bản ghi thanh toán bị thiếu hoặc mâu thuẫn.");
        }
        PaymentEntity payment = codPayments.get(0);
        if (payment.getStatus() != PaymentStatus.PENDING || payment.getAmount() != order.getTotalAmount()) {
            throw new BusinessConflictException("Không thể xác nhận COD vì bản ghi thanh toán bị thiếu hoặc mâu thuẫn.");
        }
        return payment;
    }

    private void applyCodSettlement(OrderEntity order, PaymentEntity payment, Instant now) {
        OrderStatus previous = order.getStatus();
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setProcessedAt(now);
        payment = paymentRepository.save(payment);
        order.transitionTo(OrderStatus.PAID);
        order.setPaymentStatus(PaymentStatus.SUCCEEDED);
        order.setPaidAt(now);
        order.setDeliveredAt(now);
        orderRepository.saveAndFlush(order);
        if (shopFinancialService != null) shopFinancialService.recordSale(order);
        auditLog.record("ORDER_STATUS_CHANGED", null, "Order", order.getId(),
                Map.of("from", previous.name(), "to", OrderStatus.PAID.name(), "provider", "COD"), null);
        auditLog.record("PAYMENT_STATUS_CHANGED", null, "Payment", payment.getId(),
                Map.of("status", "PENDING -> SUCCEEDED", "provider", "COD"), null);
    }

    private LockedPair lockOrderThenFulfillment(UUID fulfillmentId) {
        OrderFulfillmentEntity located = fulfillmentRepository.findById(fulfillmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        OrderEntity order = orderRepository.findByIdForUpdate(located.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        OrderFulfillmentEntity fulfillment = fulfillmentRepository.findByIdForUpdate(fulfillmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        return new LockedPair(order, fulfillment);
    }

    private record LockedPair(OrderEntity order, OrderFulfillmentEntity fulfillment) {
    }

    private void restoreInventory(OrderFulfillmentEntity fulfillment) {
        if (fulfillment.isInventoryRestored() || fulfillmentItemRepository == null
                || orderItemRepository == null || productRepository == null) return;
        for (OrderFulfillmentItemEntity link : fulfillmentItemRepository
                .findByFulfillmentIdOrderByIdAsc(fulfillment.getId())) {
            OrderItemEntity item = orderItemRepository.findById(link.getOrderItemId()).orElse(null);
            if (item == null) continue;
            ProductEntity product = productRepository.findByIdForUpdateIncludingInactive(item.getProductId()).orElse(null);
            if (product == null) continue;
            product.setStockQuantity(Math.addExact(product.getStockQuantity(), item.getQuantity()));
            productRepository.save(product);
        }
        fulfillment.setInventoryRestored(true);
        fulfillmentRepository.save(fulfillment);
    }

    private void recordAttempt(OrderFulfillmentEntity fulfillment, UUID shipperId, DeliveryAttemptOutcome outcome,
                               String reasonCode, String note, Instant nextAttemptAt, String proofUrl,
                               String proofPublicId) {
        if (attemptRepository == null) return;
        DeliveryAttemptEntity attempt = new DeliveryAttemptEntity();
        attempt.setFulfillmentId(fulfillment.getId());
        attempt.setShipperId(shipperId);
        attempt.setOutcome(outcome);
        attempt.setReasonCode(reasonCode);
        attempt.setNote(note);
        attempt.setAttemptedAt(Instant.now());
        attempt.setNextAttemptAt(nextAttemptAt);
        attempt.setProofUrl(proofUrl);
        attempt.setProofPublicId(proofPublicId);
        attemptRepository.save(attempt);
    }

    private void auditStatus(UUID actorId, UUID fulfillmentId, FulfillmentStatus from, FulfillmentStatus to,
                             RequestContext context) {
        auditLog.record("FULFILLMENT_STATUS_CHANGED", actorId, "OrderFulfillment", fulfillmentId,
                Map.of("from", from.name(), "to", to.name()), context);
    }

    private void publishStatus(OrderFulfillmentEntity fulfillment, OrderEntity order) {
        eventPublisher.publishEvent(new FulfillmentChangedEvent(
                FulfillmentChangedEvent.Type.STATUS_CHANGED, fulfillment.getId(), order.getId(),
                order.getUserId(), fulfillment.getShipperId(), fulfillment.getStatus()));
    }

    private void requireAssigned(UUID shipperId, OrderFulfillmentEntity fulfillment) {
        if (!Objects.equals(shipperId, fulfillment.getShipperId())) {
            throw new AccessDeniedException("Fulfillment này chưa được gán cho bạn.");
        }
    }

    private void requireDeliveryOwnershipAndStatus(UUID shipperId, OrderFulfillmentEntity fulfillment) {
        if (!Objects.equals(shipperId, fulfillment.getShipperId())) {
            throw new AccessDeniedException("Fulfillment này chưa được gán cho bạn.");
        }
        if (fulfillment.getStatus() != FulfillmentStatus.IN_TRANSIT) {
            throw new BusinessConflictException("Chỉ fulfillment đang giao mới có thể đánh dấu đã giao.");
        }
    }

    private void requireOrderCanBeDelivered(OrderEntity order) {
        if ((order.getStatus() != null && order.getStatus().isTerminal())
                || order.getPaymentStatus() == PaymentStatus.REFUNDED) {
            throw new BusinessConflictException("Đơn hàng đã hủy/hoàn tiền, không thể tiếp tục giao.");
        }
    }

    public record DeliveryResult(OrderFulfillmentEntity fulfillment, OrderEntity order) { }
}
