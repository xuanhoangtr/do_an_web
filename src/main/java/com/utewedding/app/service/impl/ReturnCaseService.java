package com.utewedding.app.service.impl;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.ReturnCaseStatus;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderFulfillmentEntity;
import com.utewedding.app.domain.entity.OrderItemEntity;
import com.utewedding.app.domain.entity.ReturnCaseEntity;
import com.utewedding.app.domain.entity.ReturnCaseItemEntity;
import com.utewedding.app.domain.entity.ReturnRequestIdempotencyEntity;
import com.utewedding.app.dto.request.CreateReturnCaseRequest;
import com.utewedding.app.dto.response.ReturnCaseResponse;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.OrderFulfillmentItemRepository;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.ReturnCaseItemRepository;
import com.utewedding.app.repository.ReturnCaseRepository;
import com.utewedding.app.repository.ReturnRequestIdempotencyRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReturnCaseService {
    private final OrderRepository orders;
    private final OrderFulfillmentRepository fulfillments;
    private final OrderFulfillmentItemRepository fulfillmentItems;
    private final OrderItemRepository orderItems;
    private final ReturnCaseRepository cases;
    private final ReturnCaseItemRepository caseItems;
    private final ReturnRequestIdempotencyRepository idempotency;
    private final EntityManager entityManager;
    private final ReturnPolicy policy = new ReturnPolicy();
    private org.springframework.context.ApplicationEventPublisher events;

    public ReturnCaseService(OrderRepository orders, OrderFulfillmentRepository fulfillments,
                             OrderFulfillmentItemRepository fulfillmentItems, OrderItemRepository orderItems,
                             ReturnCaseRepository cases, ReturnCaseItemRepository caseItems,
                             ReturnRequestIdempotencyRepository idempotency, EntityManager entityManager) {
        this.orders = orders; this.fulfillments = fulfillments; this.fulfillmentItems = fulfillmentItems;
        this.orderItems = orderItems; this.cases = cases; this.caseItems = caseItems;
        this.idempotency = idempotency; this.entityManager = entityManager;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachAttentionEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    @Transactional
    public ReturnCaseResponse create(UUID customerId, String idempotencyKey, CreateReturnCaseRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 190) {
            throw new BusinessConflictException("Idempotency-Key là bắt buộc.");
        }
        if(request==null||request.reason()==null||request.reason().isBlank()||request.reason().length()>80||request.items()==null||request.items().isEmpty()
                ||request.items().stream().anyMatch(i->i.orderItemId()==null||i.quantity()<=0))throw new BusinessConflictException("Nội dung hoặc số lượng trả hàng không hợp lệ.");
        String hash = hash(request);
        var previous = idempotency.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        if (previous.isPresent()) {
            if (!hash.equals(previous.get().getRequestHash())) throw new BusinessConflictException("Idempotency-Key đã dùng với payload khác.");
            if (previous.get().getReturnCaseId() == null) throw new BusinessConflictException("Yêu cầu cùng khoá đang được xử lý.");
            return toResponse(cases.findById(previous.get().getReturnCaseId()).orElseThrow());
        }
        OrderFulfillmentEntity candidate = fulfillments.findById(request.fulfillmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        OrderEntity order = orders.findByIdForUpdate(candidate.getOrderId())
                .filter(row -> row.getUserId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        previous=idempotency.findByCustomerIdAndIdempotencyKey(customerId,idempotencyKey);
        if(previous.isPresent()){
            if(!hash.equals(previous.get().getRequestHash()))throw new BusinessConflictException("Idempotency-Key đã dùng với payload khác.");
            return toResponse(cases.findById(previous.get().getReturnCaseId()).orElseThrow());
        }
        OrderFulfillmentEntity fulfillment = fulfillments.findByIdForUpdate(request.fulfillmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        if (!order.getId().equals(fulfillment.getOrderId()) || ReturnCaseQueryService.ineligible(fulfillment)!=null) throw new BusinessConflictException("Chỉ fulfillment đã giao mới được yêu cầu trả hàng.");
        Instant now = Instant.now();
        if (!policy.requestAllowed(fulfillment.getDeliveredAt(), now)) throw new BusinessConflictException("Đã quá thời hạn yêu cầu trả hàng.");
        if (request.items() == null || request.items().isEmpty()) throw new BusinessConflictException("Phải chọn ít nhất một sản phẩm.");
        Map<UUID, OrderItemEntity> itemMap = orderItems.findAllById(request.items().stream().map(CreateReturnCaseRequest.Item::orderItemId).toList())
                .stream().collect(Collectors.toMap(OrderItemEntity::getId, Function.identity()));
        List<ReturnCaseItemEntity> newItems = new ArrayList<>();
        for (CreateReturnCaseRequest.Item requested : request.items().stream().sorted(Comparator.comparing(CreateReturnCaseRequest.Item::orderItemId)).toList()) {
            OrderItemEntity item = itemMap.get(requested.orderItemId());
            if (item == null || !item.getOrderId().equals(order.getId())
                    || fulfillmentItems.findByFulfillmentIdAndOrderItemId(fulfillment.getId(), item.getId()).isEmpty()) {
                throw new BusinessConflictException("Sản phẩm không thuộc fulfillment này.");
            }
            if (newItems.stream().anyMatch(row -> row.getOrderItemId().equals(item.getId()))) throw new BusinessConflictException("Không được lặp sản phẩm.");
            int updated = entityManager.createNativeQuery("UPDATE order_items SET return_reserved_quantity = return_reserved_quantity + :quantity WHERE id = :id AND return_reserved_quantity + return_resolved_quantity + :quantity <= quantity")
                    .setParameter("quantity", requested.quantity()).setParameter("id", item.getId()).executeUpdate();
            if (updated != 1) throw new BusinessConflictException("Số lượng trả vượt quá số lượng đã mua.");
            newItems.add(new ReturnCaseItemEntity(null, item.getId(), requested.quantity()));
        }
        ReturnCaseEntity returnCase = new ReturnCaseEntity("RMA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                order.getId(), fulfillment.getId(), customerId, request.reason().trim(), policy.snapshot(), policy.requestDeadline(fulfillment.getDeliveredAt()));
        returnCase.setShopId(order.getShopId());
        cases.saveAndFlush(returnCase);
        for (ReturnCaseItemEntity item : newItems) {
            item = new ReturnCaseItemEntity(returnCase.getId(), item.getOrderItemId(), item.getRequestedQuantity());
            item.setShopId(returnCase.getShopId());
            caseItems.save(item);
        }
        ReturnRequestIdempotencyEntity claim = new ReturnRequestIdempotencyEntity(idempotencyKey, customerId, hash);
        claim.setReturnCaseId(returnCase.getId());
        idempotency.save(claim);
        attentionChanged(returnCase.getId());
        return toResponse(returnCase);
    }

    @Transactional(readOnly = true)
    public List<ReturnCaseResponse> list(UUID customerId) {
        return cases.findByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ReturnCaseResponse get(UUID customerId, UUID id) {
        return toResponse(cases.findByIdAndCustomerId(id, customerId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu trả hàng.")));
    }

    @Transactional
    public ReturnCaseResponse cancel(UUID customerId, UUID id) {
        ReturnCaseEntity returnCase = cases.findByIdAndCustomerIdForUpdate(id, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu trả hàng."));
        if (returnCase.getStatus() != ReturnCaseStatus.REQUESTED) throw new BusinessConflictException("Chỉ được hủy yêu cầu đang chờ xử lý.");
        for (ReturnCaseItemEntity item : caseItems.findByReturnCaseIdOrderByIdAsc(id)) {
            entityManager.createNativeQuery("UPDATE order_items SET return_reserved_quantity = return_reserved_quantity - :quantity WHERE id = :id AND return_reserved_quantity >= :quantity")
                    .setParameter("quantity", item.getRequestedQuantity()).setParameter("id", item.getOrderItemId()).executeUpdate();
        }
        returnCase.transitionTo(ReturnCaseStatus.CANCELLED);
        attentionChanged(returnCase.getId());
        return toResponse(returnCase);
    }

    private ReturnCaseResponse toResponse(ReturnCaseEntity value) {
        return new ReturnCaseResponse(value.getId(), value.getCaseNumber(), value.getOrderId(), value.getFulfillmentId(), value.getStatus(), value.getReason(), value.getRequestDeadline(), value.getShipDeadline(), value.getDisputeDeadline(), caseItems.findByReturnCaseIdOrderByIdAsc(value.getId()).stream().map(item -> new ReturnCaseResponse.Item(item.getOrderItemId(), item.getRequestedQuantity(), item.getApprovedQuantity(), item.getReceivedQuantity(), item.getResolvedQuantity(), item.getRefundAmount())).toList());
    }

    private void attentionChanged(UUID caseId) {
        if (events != null) events.publishEvent(
                new com.utewedding.app.event.AdminAttentionChangedEvent("ReturnCase", caseId));
    }

    private String hash(CreateReturnCaseRequest request) {
        String canonical = request.fulfillmentId() + "|" + request.reason().trim() + "|" + request.items().stream().sorted(Comparator.comparing(CreateReturnCaseRequest.Item::orderItemId)).map(item -> item.orderItemId() + ":" + item.quantity()).collect(Collectors.joining(","));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
