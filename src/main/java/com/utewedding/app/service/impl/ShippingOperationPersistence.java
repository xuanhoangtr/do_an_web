package com.utewedding.app.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.entity.CodObligationEntity;
import com.utewedding.app.domain.entity.FulfillmentShipmentEntity;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderFulfillmentEntity;
import com.utewedding.app.domain.entity.OrderShippingSnapshotEntity;
import com.utewedding.app.domain.entity.ShippingProviderOperationEntity;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.CodObligationRepository;
import com.utewedding.app.repository.FulfillmentShipmentRepository;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.OrderShippingSnapshotRepository;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.ShippingProviderOperationRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.port.ShippingProvider;
import com.utewedding.app.service.port.ShippingParcel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShippingOperationPersistence {
    private final ShippingProviderOperationRepository operations;
    private final FulfillmentShipmentRepository shipments;
    private final OrderFulfillmentRepository fulfillments;
    private final OrderRepository orders;
    private final OrderShippingSnapshotRepository shippingSnapshots;
    private final CodObligationRepository obligations;
    private final ObjectMapper json;
    private final OutboxService outbox;
    private final AuditLogPort audit;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ShippingParcelFactory parcelFactory;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OrderItemRepository orderItems;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ShippingCostPersistence shippingCosts;

    public ShippingOperationPersistence(ShippingProviderOperationRepository operations,
                                        FulfillmentShipmentRepository shipments,
                                        OrderFulfillmentRepository fulfillments,
                                        OrderRepository orders, OrderShippingSnapshotRepository shippingSnapshots,
                                        CodObligationRepository obligations,
                                        ObjectMapper json, OutboxService outbox, AuditLogPort audit) {
        this.operations = operations;
        this.shipments = shipments;
        this.fulfillments = fulfillments;
        this.orders = orders;
        this.shippingSnapshots = shippingSnapshots;
        this.obligations = obligations;
        this.json = json;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** Queues an explicit GHN Track/Cancel request using the shipment's frozen account context. */
    @Transactional
    public OperationReceipt requestProviderOperation(UUID shipmentId, String operationName,
                                                      String idempotencyKey, String requestHash,
                                                      UUID actorId, RequestContext context) {
        if (!"TRACK".equals(operationName) && !"CANCEL".equals(operationName)) {
            throw new IllegalArgumentException("Unsupported provider operation");
        }
        FulfillmentShipmentEntity shipmentProbe = shipments.findById(safe(shipmentId))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy vận đơn."));
        OrderFulfillmentEntity fulfillmentProbe = fulfillments.findById(shipmentProbe.getFulfillmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        OrderEntity order = orders.findByIdForUpdate(fulfillmentProbe.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        OrderFulfillmentEntity fulfillment = fulfillments.findByIdForUpdate(fulfillmentProbe.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        FulfillmentShipmentEntity shipment = shipments.findByIdForUpdate(shipmentProbe.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy vận đơn."));
        if (!order.getId().equals(fulfillment.getOrderId())
                || !fulfillment.getId().equals(shipment.getFulfillmentId())
                || !order.getShopId().equals(shipment.getShopId())
                || !order.getShopId().equals(fulfillment.getShopId())
                || !"GHN".equalsIgnoreCase(shipment.getProviderCode())) {
            throw new BusinessConflictException("Vận đơn GHN không khớp đơn hàng và shop đã lưu.");
        }
        requireSupportedProviderOperationContext(shipment);

        if (shipment.getProviderShipmentId() == null || shipment.getProviderShipmentId().isBlank()
                || shipment.getTrackingNumber() == null || shipment.getTrackingNumber().isBlank()) {
            throw new BusinessConflictException("Vận đơn GHN chưa có mã vận đơn để thao tác.");
        }
        if ("CANCEL".equals(operationName)) {
            if (shipment.getStatus() != FulfillmentStatus.PICKUP_PENDING
                    || fulfillment.getStatus() != FulfillmentStatus.PICKUP_PENDING) {
                throw new BusinessConflictException(
                        "Chỉ có thể hủy vận đơn GHN trước khi GHN lấy hàng; đơn đã bàn giao không thể hủy tại đây.");
            }
        } else if (shipment.getStatus().isTerminal() || fulfillment.getStatus().isTerminal()) {
            throw new BusinessConflictException("Không thể tra cứu cập nhật trạng thái cho vận đơn đã kết thúc.");
        }

        ShippingProviderOperationEntity existing = operations.findByIdempotencyKeyForUpdate(idempotencyKey)
                .orElse(null);
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash()) || !shipment.getId().equals(existing.getShipmentId())
                    || !operationName.equals(existing.getOperation())) {
                throw new BusinessConflictException("Idempotency-Key đã dùng với yêu cầu khác.");
            }
            if ("REJECTED".equals(existing.getStatus())) {
                existing.setStatus("PENDING");
                existing.setLastError(null);
                existing.setSanitizedResponse("{}");
                existing.setNextAttemptAt(Instant.now());
                enqueueRetry(existing, context);
            }
            return new OperationReceipt(existing.getId(), order.getId(), existing.getStatus());
        }

        ShippingProviderOperationEntity operation = new ShippingProviderOperationEntity();
        operation.setShipmentId(shipment.getId());
        operation.setShopId(shipment.getShopId());
        operation.setOperation(operationName);
        operation.setIdempotencyKey(idempotencyKey);
        operation.setRequestHash(requestHash);
        operation.setSanitizedRequest(toJson(Map.of("shipmentId", shipment.getId().toString(),
                "providerShipmentId", shipment.getProviderShipmentId())));
        operation = operations.saveAndFlush(operation);
        outbox.enqueue(ShippingOperationsService.EVENT, "ShippingProviderOperation", operation.getId(),
                "shipping:" + idempotencyKey, "{\"operationId\":\"" + operation.getId() + "\"}", context);
        audit.record("SHIPPING_PROVIDER_OPERATION_REQUESTED", actorId, "ShippingProviderOperation",
                operation.getId(), Map.of("operation", operationName, "provider", "GHN"), context);
        return new OperationReceipt(operation.getId(), order.getId(), operation.getStatus());
    }

    @Transactional
    public Task prepare(UUID operationId) {
        ShippingProviderOperationEntity operation = operations.findByIdForUpdate(operationId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy shipping operation."));
        if (!operation.getStatus().equals("PENDING") && !operation.getStatus().equals("FAILED")) {
            throw new BusinessConflictException("Shipping operation is not claimable.");
        }
        if (operation.getNextAttemptAt() != null && operation.getNextAttemptAt().isAfter(Instant.now())) {
            throw new BusinessConflictException("Shipping operation retry is not due yet.");
        }
        if ("TRACK".equals(operation.getOperation()) || "CANCEL".equals(operation.getOperation())) {
            FulfillmentShipmentEntity shipment = shipments.findById(safe(operation.getShipmentId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy shipment."));
            // Legacy/platform CREATE may use the global GHN account, but Track/Cancel
            // require an immutable account snapshot. Reject before marking PROCESSING.
            requireSupportedProviderOperationContext(shipment);
        }
        operation.setStatus("PROCESSING");
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        return task(operation);
    }

    @Transactional
    public Task inspect(UUID operationId) {
        ShippingProviderOperationEntity operation = operations.findByIdForUpdate(operationId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy shipping operation."));
        if (!operation.getStatus().equals("AMBIGUOUS")) return null;
        if ("TRACK".equals(operation.getOperation()) || "CANCEL".equals(operation.getOperation())) {
            FulfillmentShipmentEntity shipment = shipments.findById(safe(operation.getShipmentId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy shipment."));
            try {
                requireSupportedProviderOperationContext(shipment);
            } catch (BusinessConflictException unsupported) {
                operation.setStatus("REJECTED");
                operation.setLastError(clean(unsupported.getMessage()));
                operation.setNextAttemptAt(null);
                operation.setProcessedAt(Instant.now());
                return null;
            }
        }
        return task(operation);
    }

    @Transactional
    public void succeed(UUID operationId, ShippingProvider.ShipmentResult result) {
        CompletionRows rows = lockCompletionRows(operationId);
        ShippingProviderOperationEntity operation = rows.operation();
        if (operation.getStatus().equals("SUCCEEDED")) return;
        if (!operation.getStatus().equals("PROCESSING") && !operation.getStatus().equals("AMBIGUOUS")) {
            throw new BusinessConflictException("Shipping operation is no longer active.");
        }
        completeLocked(operation, result, rows.order(), rows.fulfillment(), rows.shipment());
    }

    @Transactional
    public TrackingReceipt succeedTracking(UUID operationId, ShippingProvider.TrackingResult result) {
        if (result == null || result.status() == null) {
            throw new BusinessConflictException("GHN Detail không trả về trạng thái vận đơn hợp lệ.");
        }
        CompletionRows rows = lockCompletionRows(operationId);
        ShippingProviderOperationEntity operation = rows.operation();
        if (!"TRACK".equals(operation.getOperation())
                || (!operation.getStatus().equals("PROCESSING") && !operation.getStatus().equals("AMBIGUOUS"))) {
            throw new BusinessConflictException("Yêu cầu tra cứu GHN không còn hoạt động.");
        }
        FulfillmentShipmentEntity shipment = rows.shipment();
        ShippingProvider.GhnShipmentContext saved = contextFromSnapshot(shipment.getProviderContextSnapshot());
        Map<String, Object> metadata = result.metadata() == null ? Map.of() : result.metadata();
        if (!"GHN".equalsIgnoreCase(shipment.getProviderCode()) || saved == null
                || shipment.getProviderAccountId() == null
                || !shipment.getProviderAccountId().equals(saved.accountId())
                || !shipment.getShopId().equals(saved.shopId())
                || !rows.order().getShopId().equals(shipment.getShopId())
                || !rows.fulfillment().getShopId().equals(shipment.getShopId())
                || !rows.fulfillment().getId().equals(shipment.getFulfillmentId())
                || saved.externalShopId() == null || saved.externalShopId().isBlank()
                || !"STAGING".equalsIgnoreCase(saved.environment())
                || shipment.getProviderShipmentId() == null || shipment.getProviderShipmentId().isBlank()
                || shipment.getTrackingNumber() == null || shipment.getTrackingNumber().isBlank()
                || !shipment.getProviderShipmentId().equals(text(metadata.get("orderCode")))
                || !saved.externalShopId().equals(text(metadata.get("shopId")))) {
            throw new BusinessConflictException("Kết quả GHN Detail không khớp tài khoản, ShopId hoặc mã vận đơn đã lưu.");
        }

        boolean staleSequence = result.sequence() > 0 && shipment.getLastProviderSequence() != null
                && result.sequence() <= shipment.getLastProviderSequence();
        boolean staleTimestamp = result.eventAt() != null && shipment.getLastProviderEventAt() != null
                && !result.eventAt().isAfter(shipment.getLastProviderEventAt());
        boolean applied = false;
        if (!staleSequence && !staleTimestamp) {
            if (!rows.fulfillment().getStatus().canTransitionTo(result.status())
                    && rows.fulfillment().getStatus() != result.status()) {
                throw new BusinessConflictException("GHN Detail trả về trạng thái không hợp lệ với luồng giao hiện tại.");
            }
            shipment.setStatus(result.status());
            if (result.sequence() > 0) shipment.setLastProviderSequence(result.sequence());
            if (result.eventAt() != null) shipment.setLastProviderEventAt(result.eventAt());
            rows.fulfillment().setStatus(result.status());
            if (result.status() == FulfillmentStatus.DELIVERED) {
                rows.fulfillment().setDeliveredAt(result.eventAt() == null ? Instant.now() : result.eventAt());
            }
            if (result.status() == FulfillmentStatus.RETURNED) {
                rows.fulfillment().setReturnedAt(result.eventAt() == null ? Instant.now() : result.eventAt());
            }
            applied = true;
        }
        operation.setProviderReference(shipment.getProviderShipmentId());
        operation.setSanitizedResponse(toJson(metadata));
        operation.setStatus("SUCCEEDED");
        operation.setProcessedAt(Instant.now());
        operation.setLastError(staleSequence || staleTimestamp ? "Stale GHN detail ignored." : null);
        if (applied) {
            audit.record("SHIPPING_TRACKING_RECONCILED", null, "FulfillmentShipment", shipment.getId(),
                    Map.of("source", "GHN_DETAIL", "status", result.status().name()),
                    new RequestContext(null, "shipping-provider-track", operation.getId().toString(), "SYSTEM"));
            enqueueCodFulfillmentUpdate(rows.order().getId(), rows.fulfillment().getId(), result.status(),
                    "shipping-cod:operation:" + operation.getId());
        }
        return new TrackingReceipt(applied ? rows.order().getId() : null,
                applied ? rows.fulfillment().getId() : null, applied ? result.status() : null, applied);
    }

    @Transactional
    public void retryAmbiguous(UUID operationId, UUID actorId, RequestContext context) {
        ShippingProviderOperationEntity operation = operations.findByIdForUpdate(operationId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy shipping operation."));
        if (!operation.getStatus().equals("AMBIGUOUS")) return;
        enqueueSafeRetry(operation, context);
        audit.record("SHIPMENT_OPERATION_CHANGED", actorId, "ShippingProviderOperation", operationId,
                Map.of("resolution", "PROVIDER_CONFIRMED_NOT_CREATED", "operation", operation.getOperation()),
                context);
    }

    @Transactional
    public void deferAmbiguous(UUID operationId, String reason) {
        operations.findByIdForUpdate(operationId).ifPresent(operation -> {
            if (!operation.getStatus().equals("AMBIGUOUS")) return;
            operation.setLastError(clean(reason));
            operation.setNextAttemptAt(Instant.now().plusSeconds(300));
        });
    }

    @Transactional
    public void resolveVerifiedAmbiguous(UUID operationId, UUID actorId,
                                         ShippingProvider.ShipmentResult result, RequestContext context) {
        if (result == null || result.providerShipmentId() == null || result.trackingNumber() == null) {
            throw new BusinessConflictException("Cần mã vận đơn đã xác minh qua GHN Detail.");
        }
        CompletionRows rows = lockCompletionRows(operationId);
        ShippingProviderOperationEntity operation = rows.operation();
        if (!operation.getStatus().equals("AMBIGUOUS")) {
            throw new BusinessConflictException("Shipping operation không còn cần xử lý thủ công.");
        }
        completeLocked(operation, result, rows.order(), rows.fulfillment(), rows.shipment());
        audit.record("SHIPMENT_OPERATION_CHANGED", actorId, "ShippingProviderOperation", operationId,
                Map.of("resolution", "GHN_DETAIL_VERIFIED", "operation", operation.getOperation()), context);
    }

    @Transactional
    public void recordProviderFailure(UUID operationId, String error, Map<String, Object> diagnostics,
                                      boolean ambiguous) {
        operations.findByIdForUpdate(operationId).ifPresent(operation -> {
            operation.setLastError(clean(error));
            operation.setSanitizedResponse(toJson(sanitizeDiagnostics(diagnostics)));
            if (ambiguous) {
                operation.setStatus("AMBIGUOUS");
                operation.setNextAttemptAt(Instant.now());
            } else {
                operation.setStatus("REJECTED");
                operation.setNextAttemptAt(null);
            }
        });
    }

    @Transactional
    public void fail(UUID operationId, String error, boolean ambiguous) {
        operations.findByIdForUpdate(operationId).ifPresent(operation -> {
            operation.setLastError(clean(error));
            if (ambiguous) {
                operation.setStatus("AMBIGUOUS");
                operation.setNextAttemptAt(Instant.now());
            } else if (operation.getAttemptCount() >= 8) {
                operation.setStatus("DEAD");
            } else {
                operation.setStatus("FAILED");
                operation.setNextAttemptAt(Instant.now().plusSeconds(
                        Math.min(3600, 30L << Math.min(6, operation.getAttemptCount()))));
            }
        });
    }

    @Transactional(readOnly = true)
    public List<ShippingProviderOperationEntity> ambiguousOperations(Instant now) {
        return operations.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("AMBIGUOUS", now);
    }

    private void enqueueSafeRetry(ShippingProviderOperationEntity operation, RequestContext context) {
        operation.setStatus("FAILED");
        operation.setLastError("Retry queued after confirmed absence of provider shipment.");
        operation.setNextAttemptAt(Instant.now());
        String key = "shipping-reconcile-retry:" + operation.getId() + ":" + operation.getAttemptCount();
        outbox.enqueue(ShippingOperationsService.EVENT, "ShippingProviderOperation", operation.getId(), key,
                "{\"operationId\":\"" + operation.getId() + "\"}", context);
    }

    private void enqueueRetry(ShippingProviderOperationEntity operation, RequestContext context) {
        String key = "shipping:" + operation.getIdempotencyKey() + ":retry:" + (operation.getAttemptCount() + 1);
        outbox.enqueue(ShippingOperationsService.EVENT, "ShippingProviderOperation", operation.getId(), key,
                "{\"operationId\":\"" + operation.getId() + "\"}", context);
    }

    private void enqueueCodFulfillmentUpdate(UUID orderId, UUID fulfillmentId, FulfillmentStatus status,
                                             String deduplicationKey) {
        if (status != FulfillmentStatus.DELIVERED && status != FulfillmentStatus.RETURNED) return;
        outbox.enqueue("SHIPPING_COD_FULFILLMENT_UPDATE", "OrderFulfillment", fulfillmentId,
                deduplicationKey, orderId + "|" + fulfillmentId + "|" + status.name(),
                new RequestContext(null, "shipping-provider", deduplicationKey, "SYSTEM"));
    }

    private CompletionRows lockCompletionRows(UUID operationId) {
        ShippingProviderOperationEntity snapshot = operations.findById(operationId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy shipping operation."));
        FulfillmentShipmentEntity shipmentProbe = shipments.findById(safe(snapshot.getShipmentId()))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy shipment."));
        OrderFulfillmentEntity fulfillmentProbe = fulfillments.findById(shipmentProbe.getFulfillmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        // Keep business locks in order -> fulfillment -> shipment order; lock operation metadata last.
        OrderEntity order = orders.findByIdForUpdate(fulfillmentProbe.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        OrderFulfillmentEntity fulfillment = fulfillments.findByIdForUpdate(fulfillmentProbe.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        FulfillmentShipmentEntity shipment = shipments.findByIdForUpdate(shipmentProbe.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy shipment."));
        ShippingProviderOperationEntity operation = operations.findByIdForUpdate(operationId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy shipping operation."));
        if (!order.getId().equals(fulfillment.getOrderId())
                || !order.getShopId().equals(fulfillment.getShopId())
                || !order.getShopId().equals(shipment.getShopId())
                || !fulfillment.getId().equals(shipment.getFulfillmentId())) {
            throw new BusinessConflictException("Fulfillment đã được chuyển sang đơn hàng khác.");
        }
        return new CompletionRows(operation, order, fulfillment, shipment);
    }

    private void completeLocked(ShippingProviderOperationEntity operation, ShippingProvider.ShipmentResult result,
                                OrderEntity order, OrderFulfillmentEntity fulfillment,
                                FulfillmentShipmentEntity shipment) {
        if (result == null) throw new BusinessConflictException("Provider thiếu kết quả shipment.");
        if (!operation.getShipmentId().equals(shipment.getId())
                || !operation.getShopId().equals(shipment.getShopId())
                || !order.getShopId().equals(fulfillment.getShopId())
                || !order.getShopId().equals(shipment.getShopId())
                || !fulfillment.getId().equals(shipment.getFulfillmentId())) {
            throw new BusinessConflictException("Shipping operation không khớp shop/fulfillment đã lưu.");
        }
        FulfillmentStatus target = result.status();
        if ("CANCEL".equals(operation.getOperation())
                && (shipment.getStatus() != FulfillmentStatus.PICKUP_PENDING
                    || fulfillment.getStatus() != FulfillmentStatus.PICKUP_PENDING
                    || target != FulfillmentStatus.CANCELLED
                    || !java.util.Objects.equals(shipment.getProviderShipmentId(), result.providerShipmentId()))) {
            throw new BusinessConflictException(
                    "Không ghi nhận hủy GHN: mã vận đơn không khớp hoặc kiện hàng đã được bàn giao.");
        }
        if (target == null || (!fulfillment.getStatus().canTransitionTo(target)
                && fulfillment.getStatus() != target)) {
            throw new BusinessConflictException("Invalid shipping transition " + fulfillment.getStatus()
                    + " -> " + target);
        }
        shipment.setProviderShipmentId(result.providerShipmentId());
        shipment.setProviderReference(result.providerReference());
        shipment.setTrackingNumber(result.trackingNumber());
        shipment.setLabelReference(result.labelReference());
        shipment.setStatus(target);
        fulfillment.setStatus(target);
        fulfillment.setTrackingNumber(result.trackingNumber());
        operation.setProviderReference(result.providerReference());
        operation.setSanitizedResponse(toJson(result.metadata()));
        operation.setStatus("SUCCEEDED");
        operation.setProcessedAt(Instant.now());
        operation.setLastError(null);
        if ("CREATE".equals(operation.getOperation()) && "GHN".equalsIgnoreCase(shipment.getProviderCode())
                && result.metadata() != null && result.metadata().get("carrierFee") instanceof Number fee
                && fee.longValue() >= 0 && shippingCosts != null) {
            shippingCosts.store(order.getId(), fee.longValue(), "CREATED", null);
        }
    }

    private Task task(ShippingProviderOperationEntity operation) {
        FulfillmentShipmentEntity shipment = shipments.findById(safe(operation.getShipmentId()))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy shipment."));
        OrderFulfillmentEntity fulfillment = fulfillments.findById(shipment.getFulfillmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        OrderEntity order = orders.findById(fulfillment.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        if (!operation.getShipmentId().equals(shipment.getId())
                || !operation.getShopId().equals(shipment.getShopId())
                || !order.getId().equals(fulfillment.getOrderId())
                || !order.getShopId().equals(fulfillment.getShopId())
                || !order.getShopId().equals(shipment.getShopId())
                || !fulfillment.getId().equals(shipment.getFulfillmentId())) {
            throw new BusinessConflictException("Shipping operation không khớp shop/fulfillment đã lưu.");
        }
        long cod = obligations.findByFulfillmentId(fulfillment.getId())
                .map(CodObligationEntity::getExpectedCollectable).orElse(0L);
        ShippingParcel parcel = null;
        if ("GHN".equalsIgnoreCase(shipment.getProviderCode()) && shippingSnapshots != null) {
            OrderShippingSnapshotEntity snapshot = shippingSnapshots.findByOrderId(order.getId()).orElse(null);
            if (snapshot != null) parcel = parcelFromSnapshot(snapshot);
        }
        if (parcel == null && "GHN".equalsIgnoreCase(shipment.getProviderCode())
                && parcelFactory != null && orderItems != null) {
            parcel = parcelFactory.fromOrderItems(orderItems.findByOrderIdOrderByIdAsc(order.getId()));
        }
        ShippingProvider.GhnShipmentContext ghnContext = contextFromSnapshot(shipment.getProviderContextSnapshot());
        if (ghnContext != null && (!"GHN".equalsIgnoreCase(shipment.getProviderCode())
                || shipment.getProviderAccountId() == null
                || !shipment.getProviderAccountId().equals(ghnContext.accountId())
                || !shipment.getShopId().equals(ghnContext.shopId())
                || !"STAGING".equalsIgnoreCase(ghnContext.environment())
                || ghnContext.externalShopId() == null || ghnContext.externalShopId().isBlank())) {
            throw new BusinessConflictException("Snapshot tài khoản GHN không khớp shipment đã lưu.");
        }
        if ("GHN".equalsIgnoreCase(shipment.getProviderCode())
                && !com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID.equals(shipment.getShopId())
                && ghnContext == null) {
            throw new BusinessConflictException("Thiếu snapshot tài khoản GHN của cửa hàng.");
        }
        return new Task(operation.getId(), operation.getOperation(), operation.getIdempotencyKey(),
                shipment.getProviderCode(), shipment.getId(), shipment.getProviderShipmentId(),
                shipment.getTrackingNumber(), shipment.getServiceCode(), order.getOrderNumber(),
                fulfillment.getId().toString(), ghnContext == null ? cod : ghnContext.expectedCodAmount(),
                ghnContext == null ? new ShippingAddressRequest(order.getShippingRecipientName(),
                order.getShippingPhone(), order.getShippingAddress(), order.getShippingProvinceCode(),
                order.getShippingWardCode()) : ghnContext.destination(), parcel,
                order.getShippingAddressCatalogSource(), ghnContext);
    }

    private ShippingParcel parcelFromSnapshot(OrderShippingSnapshotEntity snapshot) {
        try {
            var root = json.readTree(snapshot.getQuoteMetadata());
            var parcel = root == null ? null : root.get("parcel");
            if (parcel != null && !parcel.isNull()) return json.treeToValue(parcel, ShippingParcel.class);
            return parcelFactory == null || orderItems == null ? null
                    : parcelFactory.fromOrderItems(orderItems.findByOrderIdOrderByIdAsc(snapshot.getOrderId()));
        } catch (Exception exception) {
            throw new BusinessConflictException("Thiếu thông tin kiện hàng trong shipping snapshot.");
        }
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { return "{}"; }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private String clean(String value) {
        String cleaned = value == null ? "provider failure" : value.replaceAll(
                "(?i)(token|secret|authorization)\\s*[:=]\\s*\\S+", "$1=[REDACTED]");
        return cleaned.substring(0, Math.min(1000, cleaned.length()));
    }

    private Map<String, Object> sanitizeDiagnostics(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return Map.of();
        Map<String, Object> safe = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : diagnostics.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.matches("(?i).*(token|secret|authorization|password|address|phone|name).*")) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) safe.put(key, value);
            else if (value != null) safe.put(key, clean(String.valueOf(value)));
        }
        return Map.copyOf(safe);
    }

    private UUID safe(UUID id) {
        if (id == null) throw new ResourceNotFoundException("Shipment operation is incomplete.");
        return id;
    }

    public record Task(UUID operationId, String operation, String idempotencyKey, String providerCode,
                       UUID shipmentId, String providerShipmentId, String trackingNumber, String serviceCode,
                       String orderNumber, String fulfillmentReference, long codAmount,
                       ShippingAddressRequest destination, ShippingParcel parcel, String addressCatalogSource,
                       ShippingProvider.GhnShipmentContext ghnContext) {
        public Task(UUID operationId, String operation, String idempotencyKey, String providerCode,
                    UUID shipmentId, String providerShipmentId, String trackingNumber, String serviceCode,
                    String orderNumber, String fulfillmentReference, long codAmount,
                    ShippingAddressRequest destination, ShippingParcel parcel) {
            this(operationId, operation, idempotencyKey, providerCode, shipmentId, providerShipmentId,
                    trackingNumber, serviceCode, orderNumber, fulfillmentReference, codAmount, destination,
                    parcel, "GHN_STAGING", null);
        }
    }

    public record OperationReceipt(UUID operationId, UUID orderId, String status) { }
    public record TrackingReceipt(UUID orderId, UUID fulfillmentId, FulfillmentStatus status, boolean applied) { }

    private ShippingProvider.GhnShipmentContext contextFromSnapshot(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return json.readValue(raw, ShippingProvider.GhnShipmentContext.class); }
        catch (Exception e) { throw new BusinessConflictException("Snapshot context GHN của shipment không hợp lệ."); }
    }

    private void requireSupportedProviderOperationContext(FulfillmentShipmentEntity shipment) {
        ShippingProvider.GhnShipmentContext context = contextFromSnapshot(shipment.getProviderContextSnapshot());
        if (!"GHN".equalsIgnoreCase(shipment.getProviderCode())
                || shipment.getProviderAccountId() == null || context == null
                || !shipment.getProviderAccountId().equals(context.accountId())
                || !shipment.getShopId().equals(context.shopId())
                || !"STAGING".equalsIgnoreCase(context.environment())
                || context.externalShopId() == null || context.externalShopId().isBlank()) {
            throw new BusinessConflictException(
                    "GHN Track/Cancel không được hỗ trợ cho vận đơn legacy hoặc thiếu snapshot tài khoản.");
        }
    }

    private record CompletionRows(ShippingProviderOperationEntity operation, OrderEntity order,
                                  OrderFulfillmentEntity fulfillment, FulfillmentShipmentEntity shipment) { }
}
