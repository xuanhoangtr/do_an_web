/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  jakarta.persistence.EntityManager
 *  org.springframework.beans.factory.annotation.Autowired
 *  org.springframework.context.ApplicationEventPublisher
 *  org.springframework.data.domain.Page
 *  org.springframework.data.domain.PageImpl
 *  org.springframework.data.domain.Pageable
 *  org.springframework.lang.Nullable
 *  org.springframework.security.access.AccessDeniedException
 *  org.springframework.stereotype.Service
 *  org.springframework.transaction.annotation.Transactional
 *  org.springframework.web.multipart.MultipartFile
 */
package com.utewedding.app.service.impl;

import com.utewedding.app.domain.AuditAction;
import com.utewedding.app.domain.CodCollectorRules;
import com.utewedding.app.domain.DeliveryAttemptOutcome;
import com.utewedding.app.domain.DeliveryFailureReason;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.OverallFulfillmentStatus;
import com.utewedding.app.domain.PaymentMethod;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.entity.DeliveryAttemptEntity;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderFulfillmentEntity;
import com.utewedding.app.domain.entity.OrderFulfillmentItemEntity;
import com.utewedding.app.domain.entity.OrderItemEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.dto.request.AssignFulfillmentRequest;
import com.utewedding.app.dto.response.DeliveryAttemptResponse;
import com.utewedding.app.dto.response.FulfillmentItemResponse;
import com.utewedding.app.dto.response.FulfillmentResponse;
import com.utewedding.app.dto.response.FulfillmentTimelineEvent;
import com.utewedding.app.dto.response.ShipperBoardSummary;
import com.utewedding.app.dto.response.ShipperPerformanceSummary;
import com.utewedding.app.dto.response.ShipperResponse;
import com.utewedding.app.event.FulfillmentChangedEvent;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.integration.CloudinaryService;
import com.utewedding.app.integration.CloudinaryUploadResult;
import com.utewedding.app.repository.AuditLogRepository;
import com.utewedding.app.repository.DeliveryAttemptRepository;
import com.utewedding.app.repository.OrderFulfillmentItemRepository;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.OrderShippingSnapshotRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.repository.UserRepository;
import com.utewedding.app.service.FulfillmentService;
import com.utewedding.app.service.OrderIntegrityProbe;
import com.utewedding.app.service.PageableSupport;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.CodReconciliationService;
import com.utewedding.app.service.impl.FulfillmentDeliveryPersistence;
import com.utewedding.app.service.port.AuditLogPort;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FulfillmentServiceImpl
implements FulfillmentService {
    private static final List<FulfillmentStatus> SHIPPER_WORK_STATUSES = List.of(FulfillmentStatus.PROCESSING, FulfillmentStatus.PICKUP_PENDING, FulfillmentStatus.PICKED_UP, FulfillmentStatus.IN_TRANSIT, FulfillmentStatus.DELIVERY_FAILED, FulfillmentStatus.RETRY_DELIVERY, FulfillmentStatus.RETURNING);
    private static final List<OrderStatus> TERMINAL_ORDER_STATUSES = List.of(OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.REFUNDED);
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderFulfillmentRepository fulfillmentRepository;
    private final OrderFulfillmentItemRepository fulfillmentItemRepository;
    private final UserRepository userRepository;
    private final AuditLogPort auditLog;
    private final ApplicationEventPublisher eventPublisher;
    private final CloudinaryService cloudinaryService;
    private final FulfillmentDeliveryPersistence deliveryPersistence;
    private final DeliveryAttemptRepository attemptRepository;
    private final ProductRepository productRepository;
    private final AuditLogRepository auditLogRepository;
    private final EntityManager entityManager;
    private final OrderIntegrityProbe integrityProbe;
    private CodReconciliationService codReconciliationService;
    private OrderShippingSnapshotRepository shippingSnapshots;

    public FulfillmentServiceImpl(OrderRepository orderRepository, OrderItemRepository orderItemRepository, OrderFulfillmentRepository fulfillmentRepository, OrderFulfillmentItemRepository fulfillmentItemRepository, UserRepository userRepository, AuditLogPort auditLog, ApplicationEventPublisher eventPublisher, CloudinaryService cloudinaryService, FulfillmentDeliveryPersistence deliveryPersistence) {
        this(orderRepository, orderItemRepository, fulfillmentRepository, fulfillmentItemRepository, userRepository, auditLog, eventPublisher, cloudinaryService, deliveryPersistence, null, null, null, null, null);
    }

    @Autowired
    public FulfillmentServiceImpl(OrderRepository orderRepository, OrderItemRepository orderItemRepository, OrderFulfillmentRepository fulfillmentRepository, OrderFulfillmentItemRepository fulfillmentItemRepository, UserRepository userRepository, AuditLogPort auditLog, ApplicationEventPublisher eventPublisher, CloudinaryService cloudinaryService, FulfillmentDeliveryPersistence deliveryPersistence, DeliveryAttemptRepository attemptRepository, ProductRepository productRepository, AuditLogRepository auditLogRepository, EntityManager entityManager, @Nullable OrderIntegrityProbe integrityProbe) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.fulfillmentRepository = fulfillmentRepository;
        this.fulfillmentItemRepository = fulfillmentItemRepository;
        this.userRepository = userRepository;
        this.auditLog = auditLog;
        this.eventPublisher = eventPublisher;
        this.cloudinaryService = cloudinaryService;
        this.deliveryPersistence = deliveryPersistence;
        this.attemptRepository = attemptRepository;
        this.productRepository = productRepository;
        this.auditLogRepository = auditLogRepository;
        this.entityManager = entityManager;
        this.integrityProbe = integrityProbe;
    }

    @Autowired(required=false)
    void attachCodReconciliationService(CodReconciliationService service) {
        this.codReconciliationService = service;
    }

    @Autowired(required=false)
    void attachOrderShippingSnapshotRepository(OrderShippingSnapshotRepository repository) {
        this.shippingSnapshots = repository;
    }

    @Override
    @Transactional(rollbackFor={Exception.class})
    public void ensureForOrder(UUID orderId) {
        this.ensureForOrder(orderId, null);
    }

    @Override
    @Transactional(rollbackFor={Exception.class})
    public void ensureForOrder(UUID orderId, RequestContext context) {
        boolean paid;
        OrderEntity order = this.orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y \u0111\u01a1n h\u00e0ng."));
        boolean bl = paid = order.getStatus() == OrderStatus.PAID && order.getPaymentStatus() == PaymentStatus.SUCCEEDED;
        if (!paid) {
            return;
        }
        List<OrderFulfillmentEntity> existing = this.fulfillmentRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        if (existing.isEmpty()) {
            OrderFulfillmentEntity fulfillment = new OrderFulfillmentEntity();
            fulfillment.setOrderId(orderId);
            fulfillment.setStatus(FulfillmentStatus.CONFIRMED);
            fulfillment = (OrderFulfillmentEntity)this.fulfillmentRepository.saveAndFlush(fulfillment);
            this.auditLog.record("FULFILLMENT_CREATED", null, "OrderFulfillment", fulfillment.getId(), Map.of("orderId", orderId), context);
            this.eventPublisher.publishEvent((Object)new FulfillmentChangedEvent(FulfillmentChangedEvent.Type.CREATED, fulfillment.getId(), orderId, order.getUserId(), null, fulfillment.getStatus()));
            for (OrderItemEntity item : this.orderItemRepository.findByOrderIdOrderByIdAsc(orderId)) {
                OrderFulfillmentItemEntity link = new OrderFulfillmentItemEntity();
                link.setFulfillmentId(fulfillment.getId());
                link.setOrderItemId(item.getId());
                this.fulfillmentItemRepository.save(link);
            }
        }
        this.assignCodCollector(order);
    }

    @Override
    @Transactional(rollbackFor={Exception.class})
    public void confirmCodOrder(UUID actorId, UUID orderId, RequestContext context) {
        this.requireOperationsRole(actorId);
        OrderEntity order = this.orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y \u0111\u01a1n h\u00e0ng."));
        if (!PaymentMethod.COD.name().equalsIgnoreCase(order.getPaymentMethod()) || order.getStatus() != OrderStatus.PENDING || order.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new BusinessConflictException("Ch\u1ec9 \u0111\u01a1n COD \u0111ang ch\u1edd m\u1edbi \u0111\u01b0\u1ee3c x\u00e1c nh\u1eadn.");
        }
        if (order.getConfirmedAt() != null) {
            throw new BusinessConflictException("\u0110\u01a1n COD n\u00e0y \u0111\u00e3 \u0111\u01b0\u1ee3c x\u00e1c nh\u1eadn.");
        }
        List<OrderFulfillmentEntity> rows = this.fulfillmentRepository.findByOrderIdForUpdate(orderId);
        if (rows.stream().anyMatch(row -> row.getStatus() != FulfillmentStatus.CONFIRMED)) {
            throw new BusinessConflictException("\u0110\u01a1n \u0111\u00e3 b\u1eaft \u0111\u1ea7u x\u1eed l\u00fd; kh\u00f4ng th\u1ec3 x\u00e1c nh\u1eadn l\u1ea1i.");
        }
        if (rows.isEmpty()) {
            OrderFulfillmentEntity fulfillment = new OrderFulfillmentEntity();
            fulfillment.setOrderId(orderId);
            fulfillment.setStatus(FulfillmentStatus.CONFIRMED);
            fulfillment = (OrderFulfillmentEntity)this.fulfillmentRepository.saveAndFlush(fulfillment);
            this.auditLog.record("FULFILLMENT_CREATED", actorId, "OrderFulfillment", fulfillment.getId(), Map.of("orderId", orderId, "source", "COD_ORDER_CONFIRMED"), context);
            this.eventPublisher.publishEvent((Object)new FulfillmentChangedEvent(FulfillmentChangedEvent.Type.CREATED, fulfillment.getId(), orderId, order.getUserId(), null, fulfillment.getStatus()));
            for (OrderItemEntity item : this.orderItemRepository.findByOrderIdOrderByIdAsc(orderId)) {
                OrderFulfillmentItemEntity link = new OrderFulfillmentItemEntity();
                link.setFulfillmentId(fulfillment.getId());
                link.setOrderItemId(item.getId());
                this.fulfillmentItemRepository.save(link);
            }
        }
        if (this.entityManager != null) {
            this.entityManager.flush();
        }
        if (this.codReconciliationService != null) {
            this.codReconciliationService.initialize(orderId);
        }
        order.setConfirmedAt(Instant.now());
        order.setConfirmedBy(actorId);
        this.orderRepository.save(order);
        this.assignCodCollector(order);
        this.auditLog.record("ORDER_CONFIRMED", actorId, "Order", orderId, Map.of("paymentMethod", PaymentMethod.COD.name()), context);
    }

    private void assignCodCollector(OrderEntity order) {
        if (order == null || !PaymentMethod.COD.name().equalsIgnoreCase(order.getPaymentMethod())) {
            return;
        }
        ArrayList<OrderFulfillmentEntity> rows = new ArrayList<OrderFulfillmentEntity>(this.fulfillmentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId()));
        rows.sort(Comparator.comparing(OrderFulfillmentEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(OrderFulfillmentEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        if (CodCollectorRules.applyIfAbsent(rows, order.getTotalAmount())) {
            this.fulfillmentRepository.saveAll(rows);
        }
    }

    @Override
    @Transactional(readOnly=true)
    public List<FulfillmentResponse> listForOrder(UUID orderId) {
        OrderEntity order = (OrderEntity)this.orderRepository.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y \u0111\u01a1n h\u00e0ng."));
        return this.toResponses(this.fulfillmentRepository.findByOrderIdOrderByCreatedAtAsc(orderId), order);
    }

    @Override
    @Transactional(readOnly=true)
    public Page<FulfillmentResponse> listAdmin(Pageable pageable) {
        return this.toPageResponses(this.fulfillmentRepository.findAllByOrderByCreatedAtDesc(this.normalize(pageable)));
    }

    @Override
    @Transactional(readOnly=true)
    public List<ShipperResponse> listShippers() {
        return this.userRepository.findActiveShippers().stream().map(user -> new ShipperResponse(user.getId(), user.getDisplayName(), user.getEmail())).toList();
    }

    @Override
    @Transactional(readOnly=true)
    public Page<FulfillmentResponse> listForShipper(UUID shipperId, Pageable pageable) {
        return this.toPageResponses(this.fulfillmentRepository.findByShipperId(shipperId, this.normalize(pageable)));
    }

    @Override
    @Transactional(readOnly=true)
    public Page<FulfillmentResponse> listForShipperBoard(UUID shipperId, boolean history, String search, FulfillmentStatus status, Pageable pageable) {
        Pageable safePageable = this.normalize(pageable);
        String query = this.normalize(search);
        if (query == null) {
            return this.toPageResponses(this.fulfillmentRepository.findShipperBoardPage(shipperId, history, SHIPPER_WORK_STATUSES, TERMINAL_ORDER_STATUSES, PaymentStatus.REFUNDED, status, safePageable));
        }
        List<OrderFulfillmentEntity> rows = this.fulfillmentRepository.findShipperBoardRows(shipperId, history, SHIPPER_WORK_STATUSES, TERMINAL_ORDER_STATUSES, PaymentStatus.REFUNDED, status, safePageable.getSort());
        Page<FulfillmentResponse> candidatePage = this.toPageResponses((Page<OrderFulfillmentEntity>)new PageImpl(rows, safePageable, (long)rows.size()));
        List<FulfillmentResponse> matches = candidatePage.getContent().stream().filter(row -> this.matchesSearch((FulfillmentResponse)row, query)).toList();
        int from = (int)Math.min(safePageable.getOffset(), (long)matches.size());
        int to = (int)Math.min((long)from + (long)safePageable.getPageSize(), (long)matches.size());
        return new PageImpl(matches.subList(from, to), safePageable, (long)matches.size());
    }

    @Override
    @Transactional(readOnly=true)
    public ShipperBoardSummary shipperBoardSummary(UUID shipperId, Instant todayStart, Instant tomorrowStart) {
        long assigned = this.fulfillmentRepository.countActiveShipperFulfillments(shipperId, SHIPPER_WORK_STATUSES, TERMINAL_ORDER_STATUSES, PaymentStatus.REFUNDED);
        long shipping = this.fulfillmentRepository.countActiveShipperFulfillments(shipperId, List.of(FulfillmentStatus.PICKED_UP, FulfillmentStatus.IN_TRANSIT, FulfillmentStatus.RETRY_DELIVERY), TERMINAL_ORDER_STATUSES, PaymentStatus.REFUNDED);
        long deliveredToday = this.fulfillmentRepository.countByShipperIdAndStatusAndDeliveredAtGreaterThanEqualAndDeliveredAtLessThan(shipperId, FulfillmentStatus.DELIVERED, todayStart, tomorrowStart);
        return new ShipperBoardSummary(assigned, shipping, deliveredToday);
    }

    @Override
    @Transactional(rollbackFor={Exception.class})
    public FulfillmentResponse assign(UUID actorId, UUID fulfillmentId, AssignFulfillmentRequest request, RequestContext context) {
        this.requireOperationsRole(actorId);
        UserEntity shipper = this.userRepository.findById(request.shipperId()).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y shipper."));
        if (!this.hasRole(shipper, "SHIPPER")) {
            throw new BusinessConflictException("T\u00e0i kho\u1ea3n \u0111\u01b0\u1ee3c g\u00e1n kh\u00f4ng c\u00f3 vai tr\u00f2 shipper.");
        }
        OrderFulfillmentEntity located = (OrderFulfillmentEntity)this.fulfillmentRepository.findById(fulfillmentId).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y fulfillment."));
        OrderEntity order = this.orderRepository.findByIdForUpdate(located.getOrderId()).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y \u0111\u01a1n h\u00e0ng."));
        OrderFulfillmentEntity fulfillment = this.locked(fulfillmentId);
        this.requireCodOrderConfirmed(order);
        this.rejectLegacyOrDelivered(fulfillment);
        UUID previousShipper = fulfillment.getShipperId();
        fulfillment.setShipperId(shipper.getId());
        String tracking = this.normalize(request.trackingNumber());
        if (tracking != null) {
            fulfillment.setTrackingNumber(tracking);
        }
        fulfillment = (OrderFulfillmentEntity)this.fulfillmentRepository.saveAndFlush(fulfillment);
        this.auditLog.record("FULFILLMENT_ASSIGNED", actorId, "OrderFulfillment", fulfillmentId, Map.of("shipperId", String.valueOf(previousShipper) + " -> " + String.valueOf(shipper.getId())), context);
        this.publish(FulfillmentChangedEvent.Type.ASSIGNED, fulfillment, this.orderFor(fulfillment), actorId);
        if (fulfillment.getStatus() == FulfillmentStatus.PROCESSING) {
            this.transition(fulfillment, FulfillmentStatus.PICKUP_PENDING, actorId, context);
        }
        return this.toResponse(fulfillment, this.orderFor(fulfillment));
    }

    @Override
    @Transactional(rollbackFor={Exception.class})
    public FulfillmentResponse processAsAdmin(UUID actorId, UUID fulfillmentId, RequestContext context) {
        this.requireOperationsRole(actorId);
        OrderFulfillmentEntity located = (OrderFulfillmentEntity)this.fulfillmentRepository.findById(fulfillmentId).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y fulfillment."));
        OrderEntity order = this.orderRepository.findByIdForUpdate(located.getOrderId()).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y \u0111\u01a1n h\u00e0ng."));
        OrderFulfillmentEntity fulfillment = this.locked(fulfillmentId);
        this.requireCodOrderConfirmed(order);
        this.transition(fulfillment, FulfillmentStatus.PROCESSING, actorId, context);
        return this.toResponse(fulfillment, this.orderFor(fulfillment));
    }

    private void requireCodOrderConfirmed(OrderEntity order) {
        if (order != null && PaymentMethod.COD.name().equalsIgnoreCase(order.getPaymentMethod()) && order.getStatus() == OrderStatus.PENDING && order.getConfirmedAt() == null) {
            throw new BusinessConflictException("Qu\u1ea3n l\u00fd c\u1ea7n x\u00e1c nh\u1eadn \u0111\u01a1n COD tr\u01b0\u1edbc khi chu\u1ea9n b\u1ecb h\u00e0ng.");
        }
    }

    @Override
    @Transactional(rollbackFor={Exception.class})
    public FulfillmentResponse confirmPickup(UUID shipperId, UUID fulfillmentId, RequestContext context) {
        this.requireRole(shipperId, "SHIPPER");
        OrderFulfillmentEntity located = (OrderFulfillmentEntity)this.fulfillmentRepository.findById(fulfillmentId).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y fulfillment."));
        if (!Objects.equals(shipperId, located.getShipperId())) {
            throw new AccessDeniedException("Fulfillment n\u00e0y ch\u01b0a \u0111\u01b0\u1ee3c g\u00e1n cho b\u1ea1n.");
        }
        OrderEntity order = this.orderRepository.findByIdForUpdate(located.getOrderId()).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y \u0111\u01a1n h\u00e0ng."));
        if (this.entityManager != null) {
            this.entityManager.refresh((Object)order);
        }
        OrderFulfillmentEntity fulfillment = this.fulfillmentRepository.findByIdForUpdate(fulfillmentId).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y fulfillment."));
        if (this.entityManager != null) {
            this.entityManager.refresh((Object)fulfillment);
        }
        if (!Objects.equals(shipperId, fulfillment.getShipperId())) {
            throw new AccessDeniedException("Fulfillment n\u00e0y ch\u01b0a \u0111\u01b0\u1ee3c g\u00e1n cho b\u1ea1n.");
        }
        this.requireOrderCanBeDelivered(order);
        if (fulfillment.getStatus() != FulfillmentStatus.PICKUP_PENDING) {
            throw new BusinessConflictException("Ch\u1ec9 x\u00e1c nh\u1eadn nh\u1eadn h\u00e0ng khi fulfillment \u0111ang ch\u1edd l\u1ea5y h\u00e0ng.");
        }
        this.transition(fulfillment, FulfillmentStatus.PICKED_UP, shipperId, context);
        return this.toResponse(fulfillment, order);
    }

    @Override
    @Transactional(rollbackFor={Exception.class})
    public FulfillmentResponse startShipping(UUID shipperId, UUID fulfillmentId, RequestContext context) {
        this.requireRole(shipperId, "SHIPPER");
        OrderFulfillmentEntity located = (OrderFulfillmentEntity)this.fulfillmentRepository.findById(fulfillmentId).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y fulfillment."));
        if (!Objects.equals(shipperId, located.getShipperId())) {
            throw new AccessDeniedException("Fulfillment n\u00e0y ch\u01b0a \u0111\u01b0\u1ee3c g\u00e1n cho b\u1ea1n.");
        }
        this.requireTrackingForExternalProvider(located.getOrderId(), located.getTrackingNumber());
        if (this.integrityProbe != null) {
            this.integrityProbe.beforeCompetingStatusWrite("ship");
        }
        OrderEntity order = this.orderRepository.findByIdForUpdate(located.getOrderId()).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y \u0111\u01a1n h\u00e0ng."));
        if (this.entityManager != null) {
            this.entityManager.refresh((Object)order);
        }
        OrderFulfillmentEntity fulfillment = this.fulfillmentRepository.findByIdForUpdate(fulfillmentId).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y fulfillment."));
        if (this.entityManager != null) {
            this.entityManager.refresh((Object)fulfillment);
        }
        if (!Objects.equals(shipperId, fulfillment.getShipperId())) {
            throw new AccessDeniedException("Fulfillment n\u00e0y ch\u01b0a \u0111\u01b0\u1ee3c g\u00e1n cho b\u1ea1n.");
        }
        this.requireTrackingForExternalProvider(order.getId(), fulfillment.getTrackingNumber());
        this.requireOrderCanBeDelivered(order);
        this.requireCodOrderConfirmed(order);
        if (fulfillment.getStatus() == FulfillmentStatus.PICKED_UP || fulfillment.getStatus() == FulfillmentStatus.RETRY_DELIVERY) {
            this.transition(fulfillment, FulfillmentStatus.IN_TRANSIT, shipperId, context);
        } else {
            if (fulfillment.getStatus() == FulfillmentStatus.PICKUP_PENDING) {
                throw new BusinessConflictException("C\u1ea7n x\u00e1c nh\u1eadn \u0111\u00e3 nh\u1eadn h\u00e0ng tr\u01b0\u1edbc khi b\u1eaft \u0111\u1ea7u giao.");
            }
            if (fulfillment.getStatus() == FulfillmentStatus.PROCESSING) {
                throw new BusinessConflictException("Fulfillment ch\u01b0a \u0111\u01b0\u1ee3c chuy\u1ec3n sang b\u01b0\u1edbc l\u1ea5y h\u00e0ng.");
            }
        }
        return this.toResponse(fulfillment, order);
    }

    private void requireTrackingForExternalProvider(UUID orderId, String trackingNumber) {
        boolean internalDelivery;
        boolean bl = internalDelivery = this.shippingSnapshots != null && orderId != null && this.shippingSnapshots.findByOrderId(orderId).map(snapshot -> "INTERNAL".equalsIgnoreCase(snapshot.getProviderCode())).orElse(false) != false;
        if (!internalDelivery && this.normalize(trackingNumber) == null) {
            throw new BusinessConflictException("C\u1ea7n tracking number tr\u01b0\u1edbc khi b\u1eaft \u0111\u1ea7u giao h\u00e0ng.");
        }
    }

    @Override
    public FulfillmentResponse deliver(UUID shipperId, UUID fulfillmentId, MultipartFile proof, RequestContext context) {
        return this.deliver(shipperId, fulfillmentId, proof, null, null, context);
    }

    @Override
    public FulfillmentResponse deliver(UUID shipperId, UUID fulfillmentId, MultipartFile proof, Long collectedAmount, String collectionReference, RequestContext context) {
        this.requireRole(shipperId, "SHIPPER");
        FulfillmentDeliveryPersistence.DeliveryResult replay = this.deliveryPersistence.deliveredReplay(shipperId, fulfillmentId, collectedAmount, collectionReference);
        if (replay != null) {
            return this.toDetailedResponse(replay.fulfillment(), replay.order());
        }
        this.deliveryPersistence.validateDelivery(shipperId, fulfillmentId);
        if (proof == null || proof.isEmpty()) {
            throw new BusinessConflictException("\u1ea2nh b\u1eb1ng ch\u1ee9ng giao h\u00e0ng l\u00e0 b\u1eaft bu\u1ed9c.");
        }
        this.cloudinaryService.validateImage(proof);
        CloudinaryUploadResult upload = this.cloudinaryService.uploadImage(proof, fulfillmentId.toString(), "DELIVERY_PROOF");
        try {
            FulfillmentDeliveryPersistence.DeliveryResult result = this.deliveryPersistence.persist(shipperId, fulfillmentId, upload, collectedAmount, collectionReference, context);
            return this.toResponse(result.fulfillment(), result.order());
        }
        catch (RuntimeException exception) {
            this.cloudinaryService.deleteImageQuietly(upload.publicId());
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly=true)
    public OverallFulfillmentStatus overallForOrder(UUID orderId) {
        return OverallFulfillmentStatus.from(this.fulfillmentRepository.findByOrderIdOrderByCreatedAtAsc(orderId).stream().map(OrderFulfillmentEntity::getStatus).toList());
    }

    private void transition(OrderFulfillmentEntity fulfillment, FulfillmentStatus target, UUID actorId, RequestContext context) {
        FulfillmentStatus previous;
        if (this.entityManager != null) {
            this.entityManager.refresh((Object)fulfillment);
        }
        if ((previous = fulfillment.getStatus()) == target) {
            return;
        }
        if (!previous.canTransitionTo(target)) {
            throw new BusinessConflictException("Kh\u00f4ng th\u1ec3 chuy\u1ec3n fulfillment t\u1eeb " + String.valueOf((Object)previous) + " sang " + String.valueOf((Object)target) + ".");
        }
        int updated = this.fulfillmentRepository.compareAndSetStatus(fulfillment.getId(), previous.name(), target.name(), fulfillment.getVersion(), Instant.now());
        if (updated != 1) {
            throw new BusinessConflictException("Fulfillment \u0111\u00e3 \u0111\u01b0\u1ee3c c\u1eadp nh\u1eadt b\u1edfi m\u1ed9t thao t\u00e1c kh\u00e1c.");
        }
        if (this.entityManager != null) {
            this.entityManager.detach((Object)fulfillment);
        }
        fulfillment.setStatus(target);
        if (this.entityManager == null) {
            fulfillment = (OrderFulfillmentEntity)this.fulfillmentRepository.saveAndFlush(fulfillment);
        }
        this.auditLog.record("FULFILLMENT_STATUS_CHANGED", actorId, "OrderFulfillment", fulfillment.getId(), Map.of("from", previous.name(), "to", target.name()), context);
        this.publish(target == FulfillmentStatus.DELIVERED ? FulfillmentChangedEvent.Type.DELIVERED : FulfillmentChangedEvent.Type.STATUS_CHANGED, fulfillment, this.orderFor(fulfillment), actorId);
    }

    private OrderFulfillmentEntity locked(UUID id) {
        return this.fulfillmentRepository.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y fulfillment."));
    }

    private OrderEntity orderFor(OrderFulfillmentEntity fulfillment) {
        return (OrderEntity)this.orderRepository.findById(fulfillment.getOrderId()).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y \u0111\u01a1n h\u00e0ng."));
    }

    private void requireOrderCanBeDelivered(OrderEntity order) {
        if (order.getStatus() != null && order.getStatus().isTerminal() || order.getPaymentStatus() == PaymentStatus.REFUNDED) {
            throw new BusinessConflictException("\u0110\u01a1n h\u00e0ng \u0111\u00e3 h\u1ee7y/ho\u00e0n ti\u1ec1n, kh\u00f4ng th\u1ec3 ti\u1ebfp t\u1ee5c giao.");
        }
    }

    private void rejectLegacyOrDelivered(OrderFulfillmentEntity fulfillment) {
        if (fulfillment.getStatus() == FulfillmentStatus.DELIVERED || fulfillment.getStatus() == FulfillmentStatus.CANCELLED || fulfillment.getStatus() == FulfillmentStatus.RETURNED) {
            throw new BusinessConflictException("Fulfillment \u0111\u00e3 k\u1ebft th\u00fac ho\u1eb7c l\u00e0 d\u1eef li\u1ec7u legacy.");
        }
    }

    private void requireOperationsRole(UUID actorId) {
        UserEntity actor = this.userRepository.findById(actorId).orElseThrow(() -> new AccessDeniedException("T\u00e0i kho\u1ea3n kh\u00f4ng t\u1ed3n t\u1ea1i."));
        if (!(this.hasRole(actor, "ADMIN") || this.hasRole(actor, "MANAGER") || this.hasRole(actor, "SUPER_ADMIN"))) {
            throw new AccessDeniedException("T\u00e0i kho\u1ea3n kh\u00f4ng c\u00f3 quy\u1ec1n v\u1eadn h\u00e0nh fulfillment.");
        }
    }

    private void requireRole(UUID actorId, String expected) {
        UserEntity actor = this.userRepository.findById(actorId).orElseThrow(() -> new AccessDeniedException("T\u00e0i kho\u1ea3n kh\u00f4ng t\u1ed3n t\u1ea1i."));
        if (!this.hasRole(actor, expected)) {
            throw new AccessDeniedException("T\u00e0i kho\u1ea3n kh\u00f4ng c\u00f3 vai tr\u00f2 " + expected + ".");
        }
    }

    private boolean hasRole(UserEntity user, String expected) {
        return user != null && user.getRole() != null && expected.equals(user.getRole().name());
    }

    private void publish(FulfillmentChangedEvent.Type type, OrderFulfillmentEntity fulfillment, OrderEntity order, UUID actorId) {
        this.eventPublisher.publishEvent((Object)new FulfillmentChangedEvent(type, fulfillment.getId(), order.getId(), order.getUserId(), fulfillment.getShipperId(), fulfillment.getStatus()));
    }

    private List<FulfillmentResponse> toResponses(Collection<OrderFulfillmentEntity> rows, OrderEntity order) {
        Map<UUID, List<FulfillmentItemResponse>> itemsByFulfillment = this.batchFulfillmentItems(rows);
        return rows.stream().map(row -> this.toResponse((OrderFulfillmentEntity)row, order, itemsByFulfillment.getOrDefault(row.getId(), List.of()))).toList();
    }

    private FulfillmentResponse toResponse(OrderFulfillmentEntity row, OrderEntity order) {
        return this.toResponse(row, order, this.batchFulfillmentItems(List.of(row)).getOrDefault(row.getId(), List.of()));
    }

    private FulfillmentResponse toResponse(OrderFulfillmentEntity row, OrderEntity order, List<FulfillmentItemResponse> items) {
        List<UUID> itemIds = items.stream().map(FulfillmentItemResponse::orderItemId).toList();
        return new FulfillmentResponse(row.getId(), row.getOrderId(), order == null ? null : order.getOrderNumber(), row.getStatus(), row.getShipperId(), row.getTrackingNumber(), row.getDeliveredAt(), row.getDeliveryProofUrl(), row.getCreatedAt(), row.getUpdatedAt(), itemIds, order == null ? null : order.getShippingRecipientName(), order == null ? null : order.getShippingPhone(), order == null ? null : order.getShippingAddress(), order == null ? null : order.getShippingWard(), order == null ? null : order.getShippingDistrict(), order == null ? null : order.getShippingProvince(), order == null ? null : order.getStatus(), order == null ? null : order.getPaymentStatus(), items, this.paymentMethod(order), row.isCollectsCod(), row.getCodAmount(), List.of(), List.of());
    }

    private FulfillmentResponse toDetailedResponse(OrderFulfillmentEntity row, OrderEntity order) {
        FulfillmentResponse base = this.toResponse(row, order);
        return new FulfillmentResponse(base.id(), base.orderId(), base.orderNumber(), base.status(), base.shipperId(), base.trackingNumber(), base.deliveredAt(), base.deliveryProofUrl(), base.createdAt(), base.updatedAt(), base.orderItemIds(), base.shippingRecipientName(), base.shippingPhone(), base.shippingAddress(), base.shippingWard(), base.shippingDistrict(), base.shippingProvince(), base.orderStatus(), base.orderPaymentStatus(), base.items(), base.paymentMethod(), base.collectsCod(), base.codAmount(), this.attempts(row.getId()), this.timeline(row.getId()), this.codReconciliationService == null ? null : this.codReconciliationService.collectionReceiptCode(row.getId()));
    }

    private Page<FulfillmentResponse> toPageResponses(Page<OrderFulfillmentEntity> page) {
        if (page.isEmpty()) {
            return new PageImpl(List.of(), page.getPageable(), page.getTotalElements());
        }
        List<UUID> orderIds = page.getContent().stream().map(OrderFulfillmentEntity::getOrderId).distinct().toList();
        HashMap orders = new HashMap();
        this.orderRepository.findAllById(orderIds).forEach(order -> orders.put(order.getId(), order));
        Map<UUID, List<FulfillmentItemResponse>> itemsByFulfillment = this.batchFulfillmentItems(page.getContent());
        return page.map(row -> this.toResponse((OrderFulfillmentEntity)row, (OrderEntity)orders.get(row.getOrderId()), itemsByFulfillment.getOrDefault(row.getId(), List.of())));
    }

    private Map<UUID, List<FulfillmentItemResponse>> batchFulfillmentItems(Collection<OrderFulfillmentEntity> rows) {
        List<UUID> ids = rows.stream().map(OrderFulfillmentEntity::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<UUID, List<FulfillmentItemResponse>> grouped = new LinkedHashMap<UUID, List<FulfillmentItemResponse>>();
        for (Object[] row : this.fulfillmentItemRepository.findResponseRowsByFulfillmentIdIn(ids)) {
            UUID fulfillmentId = (UUID)row[0];
            grouped.computeIfAbsent(fulfillmentId, ignored -> new ArrayList()).add(new FulfillmentItemResponse((UUID)row[1], (String)row[2], FulfillmentServiceImpl.numberInt(row[3]), (String)row[4], FulfillmentServiceImpl.numberLong(row[5]), FulfillmentServiceImpl.numberLong(row[6]), (String)row[7]));
        }
        return grouped;
    }

    private static int numberInt(Object value) {
        int n;
        if (value instanceof Number) {
            Number number = (Number)value;
            n = number.intValue();
        } else {
            n = 0;
        }
        return n;
    }

    private static long numberLong(Object value) {
        long l;
        if (value instanceof Number) {
            Number number = (Number)value;
            l = number.longValue();
        } else {
            l = 0L;
        }
        return l;
    }

    @Override
    @Transactional(readOnly=true)
    public FulfillmentResponse getForActor(UUID actorId, UUID fulfillmentId) {
        OrderEntity order;
        OrderFulfillmentEntity fulfillment;
        UserEntity actor = this.userRepository.findById(actorId).orElseThrow(() -> new AccessDeniedException("T\u00e0i kho\u1ea3n kh\u00f4ng t\u1ed3n t\u1ea1i."));
        if (!this.canRead(actor, fulfillment = (OrderFulfillmentEntity)this.fulfillmentRepository.findById(fulfillmentId).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y fulfillment.")), order = this.orderFor(fulfillment))) {
            throw new AccessDeniedException("B\u1ea1n kh\u00f4ng c\u00f3 quy\u1ec1n xem fulfillment n\u00e0y.");
        }
        return this.toDetailedResponse(fulfillment, order);
    }

    @Override
    @Transactional(readOnly=true)
    public Page<FulfillmentResponse> searchAdmin(String query, FulfillmentStatus status, UUID shipperId, Instant from, Instant to, Pageable pageable) {
        return this.toPageResponses(this.fulfillmentRepository.searchForAdmin(this.blankToNull(query), status, shipperId, from, to, this.normalize(pageable)));
    }

    @Override
    public FulfillmentResponse failDelivery(UUID shipperId, UUID fulfillmentId, String reasonCode, String note, Instant nextAttemptAt, MultipartFile proof, RequestContext context) {
        DeliveryFailureReason reason = DeliveryFailureReason.parse(reasonCode);
        if (reason == null) {
            throw new BusinessConflictException("L\u00fd do giao th\u1ea5t b\u1ea1i l\u00e0 b\u1eaft bu\u1ed9c.");
        }
        this.requireRole(shipperId, "SHIPPER");
        this.deliveryPersistence.validateFailure(shipperId, fulfillmentId);
        UploadedProof uploaded = this.uploadOptional(proof, fulfillmentId, "DELIVERY_FAILURE");
        try {
            FulfillmentDeliveryPersistence.DeliveryResult result = this.deliveryPersistence.fail(shipperId, fulfillmentId, reason.name(), this.normalize(note), nextAttemptAt, uploaded == null ? null : uploaded.url(), uploaded == null ? null : uploaded.publicId(), context);
            return this.toDetailedResponse(result.fulfillment(), result.order());
        }
        catch (RuntimeException exception) {
            if (uploaded != null) {
                this.cloudinaryService.deleteImageQuietly(uploaded.publicId());
            }
            throw exception;
        }
    }

    @Override
    @Transactional(rollbackFor={Exception.class})
    public FulfillmentResponse retryDelivery(UUID shipperId, UUID fulfillmentId, Instant nextAttemptAt, RequestContext context) {
        this.requireRole(shipperId, "SHIPPER");
        OrderFulfillmentEntity fulfillment = this.locked(fulfillmentId);
        if (!Objects.equals(shipperId, fulfillment.getShipperId())) {
            throw new AccessDeniedException("Fulfillment n\u00e0y ch\u01b0a \u0111\u01b0\u1ee3c g\u00e1n cho b\u1ea1n.");
        }
        this.transition(fulfillment, FulfillmentStatus.RETRY_DELIVERY, shipperId, context);
        fulfillment = this.locked(fulfillmentId);
        this.transition(fulfillment, FulfillmentStatus.IN_TRANSIT, shipperId, context);
        this.recordAttempt(fulfillment, shipperId, DeliveryAttemptOutcome.RETRY_SCHEDULED, null, null, nextAttemptAt, null, null);
        return this.toDetailedResponse(fulfillment, this.orderFor(fulfillment));
    }

    @Override
    @Transactional(rollbackFor={Exception.class})
    public FulfillmentResponse startReturn(UUID shipperId, UUID fulfillmentId, String note, RequestContext context) {
        this.requireRole(shipperId, "SHIPPER");
        OrderFulfillmentEntity fulfillment = this.locked(fulfillmentId);
        if (!Objects.equals(shipperId, fulfillment.getShipperId())) {
            throw new AccessDeniedException("Fulfillment n\u00e0y ch\u01b0a \u0111\u01b0\u1ee3c g\u00e1n cho b\u1ea1n.");
        }
        this.transition(fulfillment, FulfillmentStatus.RETURNING, shipperId, context);
        this.recordAttempt(fulfillment, shipperId, DeliveryAttemptOutcome.RETURN_STARTED, null, this.normalize(note), null, null, null);
        return this.toDetailedResponse(fulfillment, this.orderFor(fulfillment));
    }

    @Override
    public FulfillmentResponse completeReturn(UUID shipperId, UUID fulfillmentId, MultipartFile proof, String note, RequestContext context) {
        this.requireRole(shipperId, "SHIPPER");
        if (proof == null || proof.isEmpty()) {
            throw new BusinessConflictException("\u1ea2nh b\u1eb1ng ch\u1ee9ng ho\u00e0n h\u00e0ng l\u00e0 b\u1eaft bu\u1ed9c.");
        }
        this.deliveryPersistence.validateReturn(shipperId, fulfillmentId);
        this.cloudinaryService.validateImage(proof);
        CloudinaryUploadResult upload = this.cloudinaryService.uploadImage(proof, fulfillmentId.toString(), "RETURN_PROOF");
        try {
            FulfillmentDeliveryPersistence.DeliveryResult result = this.deliveryPersistence.completeReturn(shipperId, fulfillmentId, upload, this.normalize(note), context);
            return this.toDetailedResponse(result.fulfillment(), result.order());
        }
        catch (RuntimeException exception) {
            this.cloudinaryService.deleteImageQuietly(upload.publicId());
            throw exception;
        }
    }

    @Override
    @Transactional(rollbackFor={Exception.class})
    public FulfillmentResponse reassignCodCollector(UUID actorId, UUID orderId, UUID fulfillmentId, RequestContext context) {
        this.requireOperationsRole(actorId);
        OrderEntity order = this.orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y \u0111\u01a1n h\u00e0ng."));
        if (!PaymentMethod.COD.name().equalsIgnoreCase(order.getPaymentMethod())) {
            throw new BusinessConflictException("Ch\u1ec9 \u0111\u01a1n COD m\u1edbi c\u00f3 \u0111\u1ea7u m\u1ed1i thu h\u1ed9.");
        }
        List<OrderFulfillmentEntity> rows = this.fulfillmentRepository.findByOrderIdForUpdate(orderId);
        if (!CodCollectorRules.canReassign(rows.stream().map(OrderFulfillmentEntity::getStatus).toList())) {
            throw new BusinessConflictException("Kh\u00f4ng th\u1ec3 \u0111\u1ed5i \u0111\u1ea7u m\u1ed1i COD sau khi \u0111\u00e3 b\u1eaft \u0111\u1ea7u giao.");
        }
        try {
            CodCollectorRules.reassign(rows, fulfillmentId, order.getTotalAmount());
        }
        catch (IllegalArgumentException exception) {
            throw new ResourceNotFoundException("Fulfillment kh\u00f4ng thu\u1ed9c \u0111\u01a1n h\u00e0ng n\u00e0y.");
        }
        this.fulfillmentRepository.saveAll(rows);
        this.auditLog.record("COD_COLLECTOR_CHANGED", actorId, "Order", orderId, Map.of("fulfillmentId", fulfillmentId.toString()), context);
        OrderFulfillmentEntity selected = rows.stream().filter(row -> fulfillmentId.equals(row.getId())).findFirst().orElseThrow();
        return this.toDetailedResponse(selected, order);
    }

    @Override
    @Transactional(readOnly=true)
    public ShipperPerformanceSummary shipperPerformance(UUID shipperId) {
        if (this.attemptRepository == null) {
            return new ShipperPerformanceSummary(0L, 0L, 0L, 0L, 0L, 0.0);
        }
        long attempts = this.attemptRepository.countByShipperId(shipperId);
        long delivered = this.attemptRepository.countByShipperIdAndOutcome(shipperId, DeliveryAttemptOutcome.DELIVERED);
        long failed = this.attemptRepository.countByShipperIdAndOutcome(shipperId, DeliveryAttemptOutcome.FAILED);
        long rescheduled = this.attemptRepository.countByShipperIdAndOutcome(shipperId, DeliveryAttemptOutcome.RETRY_SCHEDULED);
        long returned = this.attemptRepository.countByShipperIdAndOutcome(shipperId, DeliveryAttemptOutcome.RETURNED);
        double rate = attempts == 0L ? 0.0 : (double)delivered * 100.0 / (double)attempts;
        return new ShipperPerformanceSummary(attempts, delivered, failed, rescheduled, returned, rate);
    }

    private boolean canRead(UserEntity actor, OrderFulfillmentEntity fulfillment, OrderEntity order) {
        if (actor.getRole() == UserRole.ADMIN || actor.getRole() == UserRole.MANAGER || actor.getRole() == UserRole.SUPER_ADMIN) {
            return true;
        }
        if (actor.getRole() == UserRole.SHIPPER) {
            return Objects.equals(actor.getId(), fulfillment.getShipperId());
        }
        if (actor.getRole() == UserRole.USER || actor.getRole() == UserRole.CUSTOMER) {
            return Objects.equals(actor.getId(), order.getUserId());
        }
        return false;
    }

    private void recordAttempt(OrderFulfillmentEntity fulfillment, UUID shipperId, DeliveryAttemptOutcome outcome, String reasonCode, String note, Instant nextAttemptAt, String proofUrl, String proofPublicId) {
        if (this.attemptRepository == null) {
            return;
        }
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
        this.attemptRepository.save(attempt);
    }

    private List<DeliveryAttemptResponse> attempts(UUID fulfillmentId) {
        if (this.attemptRepository == null || fulfillmentId == null) {
            return List.of();
        }
        return this.attemptRepository.findByFulfillmentIdOrderByAttemptedAtAsc(fulfillmentId).stream().map(attempt -> new DeliveryAttemptResponse(attempt.getId(), attempt.getShipperId(), attempt.getOutcome(), attempt.getReasonCode(), attempt.getNote(), attempt.getAttemptedAt(), attempt.getNextAttemptAt(), attempt.getProofUrl())).toList();
    }

    private List<FulfillmentTimelineEvent> timeline(UUID fulfillmentId) {
        if (this.auditLogRepository == null || fulfillmentId == null) {
            return List.of();
        }
        return this.auditLogRepository.findByTargetTypeAndTargetIdAndActionOrderByCreatedAtAsc("OrderFulfillment", fulfillmentId, AuditAction.FULFILLMENT_STATUS_CHANGED).stream().map(event -> new FulfillmentTimelineEvent("Giao h\u00e0ng", this.metadataText(event.getMetadata(), "from"), this.metadataText(event.getMetadata(), "to"), event.getCreatedAt(), event.getActorId(), this.metadataText(event.getMetadata(), "note") == null ? this.metadataText(event.getMetadata(), "reason") : this.metadataText(event.getMetadata(), "note"))).toList();
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? null : value.toString();
    }

    private PaymentMethod paymentMethod(OrderEntity order) {
        if (order == null || order.getPaymentMethod() == null) {
            return null;
        }
        try {
            return PaymentMethod.valueOf(order.getPaymentMethod());
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private UploadedProof uploadOptional(MultipartFile proof, UUID fulfillmentId, String kind) {
        if (proof == null || proof.isEmpty()) {
            return null;
        }
        this.cloudinaryService.validateImage(proof);
        CloudinaryUploadResult upload = this.cloudinaryService.uploadImage(proof, fulfillmentId.toString(), kind);
        return new UploadedProof(upload.secureUrl(), upload.publicId());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean matchesSearch(FulfillmentResponse row, String search) {
        String lowerSearch = search.toLowerCase(Locale.ROOT);
        if (this.containsIgnoreCase(row.orderNumber(), lowerSearch) || this.containsIgnoreCase(row.trackingNumber(), lowerSearch) || this.containsIgnoreCase(row.shippingRecipientName(), lowerSearch) || this.containsIgnoreCase(row.shippingPhone(), lowerSearch)) {
            return true;
        }
        String searchDigits = search.replaceAll("\\D", "");
        String phoneDigits = row.shippingPhone() == null ? "" : row.shippingPhone().replaceAll("\\D", "");
        return !searchDigits.isEmpty() && phoneDigits.contains(searchDigits);
    }

    private boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private Pageable normalize(Pageable pageable) {
        return PageableSupport.normalize(pageable, "createdAt", Set.of("createdAt", "updatedAt", "status", "shipperId"));
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record UploadedProof(String url, String publicId) {
    }
}
