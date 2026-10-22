package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ReturnCaseStatus;
import com.utewedding.app.domain.ReturnDisputeStatus;
import com.utewedding.app.domain.ReturnShipmentDirection;
import com.utewedding.app.domain.ReturnShipmentStatus;
import com.utewedding.app.domain.StockDisposition;
import com.utewedding.app.domain.UserRole;
import com.utewedding.app.domain.entity.ReturnCaseEntity;
import com.utewedding.app.domain.entity.ReturnCaseItemEntity;
import com.utewedding.app.domain.entity.ReturnDisputeEntity;
import com.utewedding.app.domain.entity.ReturnShipmentEntity;
import com.utewedding.app.domain.entity.UserEntity;
import com.utewedding.app.dto.request.ReturnInspectionRequest;
import com.utewedding.app.dto.request.ReturnReceiveRequest;
import com.utewedding.app.dto.request.ReturnReviewRequest;
import com.utewedding.app.dto.request.ReturnShipmentRequest;
import com.utewedding.app.dto.request.ReturnDisputeDecisionRequest;
import com.utewedding.app.dto.request.ReturnDisputeRequest;
import com.utewedding.app.dto.response.ReturnCaseResponse;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.ReturnCaseHistoryRepository;
import com.utewedding.app.repository.ReturnCaseItemRepository;
import com.utewedding.app.repository.ReturnCaseRepository;
import com.utewedding.app.repository.ReturnDisputeRepository;
import com.utewedding.app.repository.ReturnShipmentRepository;
import com.utewedding.app.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReturnCaseOperationsService {
    private final ReturnCaseRepository cases;
    private final ReturnCaseItemRepository items;
    private final ReturnCaseHistoryRepository history;
    private final UserRepository users;
    private final EntityManager entityManager;
    private final ReturnShipmentRepository shipments;
    private final ReturnDisputeRepository disputes;
    private org.springframework.context.ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.utewedding.app.repository.FulfillmentShipmentRepository fulfillmentShipments;

    public ReturnCaseOperationsService(ReturnCaseRepository cases, ReturnCaseItemRepository items,
                                       ReturnCaseHistoryRepository history, UserRepository users,
                                       EntityManager entityManager, ReturnShipmentRepository shipments,
                                       ReturnDisputeRepository disputes) {
        this.cases = cases; this.items = items; this.history = history; this.users = users;
        this.entityManager = entityManager;
        this.shipments = shipments; this.disputes = disputes;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachAttentionEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    @Transactional
    public ReturnCaseResponse claim(UUID actorId, UUID caseId, String requestId) {
        ReturnCaseEntity value = locked(actorId, caseId);
        if(value.getStatus()!=ReturnCaseStatus.REQUESTED)throw new BusinessConflictException("Yêu cầu đã được tiếp nhận.");
        transition(value, ReturnCaseStatus.UNDER_REVIEW, actorId, requestId, "{}");
        return response(value);
    }

    @Transactional
    public boolean expireAwaitingReturn(UUID caseId, Instant now) {
        ReturnCaseEntity value = cases.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu trả hàng."));
        if (value.getStatus() != ReturnCaseStatus.AWAITING_RETURN
                || value.getShipDeadline() == null || value.getShipDeadline().isAfter(now)) return false;
        for (ReturnCaseItemEntity item : items.findByReturnCaseIdOrderByIdAsc(caseId)) {
            int outstandingReservation = Math.max(0, item.getApprovedQuantity() - item.getResolvedQuantity());
            releaseReservation(item.getOrderItemId(), outstandingReservation);
        }
        transition(value, ReturnCaseStatus.EXPIRED, null, "return-expiry-job", "{\"reason\":\"SHIP_DEADLINE\"}");
        return true;
    }

    @Transactional
    public ReturnCaseResponse review(UUID actorId, UUID caseId, ReturnReviewRequest request, String requestId) {
        ReturnCaseEntity value = locked(actorId, caseId);
        if(request==null||request.approve()==null||request.reason()!=null&&request.reason().length()>1000)throw new BusinessConflictException("Cần chọn quyết định xử lý.");
        if (value.getStatus() != ReturnCaseStatus.UNDER_REVIEW) throw new BusinessConflictException("Case chưa ở trạng thái review.");
        Map<UUID, ReturnReviewRequest.Item> requested = (request.items() == null ? java.util.List.<ReturnReviewRequest.Item>of() : request.items())
                .stream().collect(Collectors.toMap(ReturnReviewRequest.Item::orderItemId, Function.identity(), (left, right) -> { throw new BusinessConflictException("Không được lặp sản phẩm."); }));
        for (ReturnCaseItemEntity item : items.findByReturnCaseIdOrderByIdAsc(caseId)) {
            int approved = requested.getOrDefault(item.getOrderItemId(), new ReturnReviewRequest.Item(item.getOrderItemId(), 0)).approvedQuantity();
            if (approved < 0 || approved > item.getRequestedQuantity()) throw new BusinessConflictException("Số lượng duyệt vượt số lượng yêu cầu.");
            item.setApprovedQuantity(approved);
            if (Boolean.TRUE.equals(request.approve())) {
                releaseReservation(item.getOrderItemId(), item.getRequestedQuantity() - approved);
            }
            items.save(item);
        }
        if (Boolean.FALSE.equals(request.approve())) {
            if (request.reason() == null || request.reason().isBlank()) throw new BusinessConflictException("Từ chối phải có lý do.");
            for (ReturnCaseItemEntity item : items.findByReturnCaseIdOrderByIdAsc(caseId)) {
                releaseReservation(item.getOrderItemId(), item.getRequestedQuantity());
            }
            value.setRejectionReason(request.reason().trim());
            value.setDisputeDeadline(Instant.now().plus(ReturnPolicy.DISPUTE_WINDOW));
            transition(value, ReturnCaseStatus.REJECTED, actorId, requestId, "{\"decision\":\"REJECTED\"}");
        } else {
            boolean any = items.findByReturnCaseIdOrderByIdAsc(caseId).stream().anyMatch(item -> item.getApprovedQuantity() > 0);
            if (!any) throw new BusinessConflictException("Phải duyệt ít nhất một quantity.");
            value.setReturnRequired(request.returnRequired());
            transition(value, ReturnCaseStatus.APPROVED, actorId, requestId, "{\"decision\":\"APPROVED\"}");
            if (request.returnRequired()) {
                value.setShipDeadline(Instant.now().plus(ReturnPolicy.SHIP_WINDOW));
                shipments.findByReturnCaseIdAndDirection(caseId, ReturnShipmentDirection.CUSTOMER_TO_SHOP)
                        .orElseGet(() -> createShipment(value, ReturnShipmentDirection.CUSTOMER_TO_SHOP));
                transition(value, ReturnCaseStatus.AWAITING_RETURN, actorId, requestId, "{\"decision\":\"APPROVED\"}");
            } else {
                transition(value, ReturnCaseStatus.RESOLUTION_PENDING, actorId, requestId, "{\"decision\":\"APPROVED_NO_RETURN\"}");
            }
        }
        return response(value);
    }

    @Transactional
    public ReturnCaseResponse receive(UUID actorId, UUID caseId, ReturnReceiveRequest request, String requestId) {
        ReturnCaseEntity value = locked(actorId, caseId);
        if (value.getStatus() != ReturnCaseStatus.RETURN_IN_TRANSIT) throw new BusinessConflictException("Shipment chưa ở trạng thái đang vận chuyển.");
        if(request==null||request.items()==null||request.items().isEmpty())throw new BusinessConflictException("Cần nhập số lượng hàng nhận.");
        Map<UUID, ReturnReceiveRequest.Item> received = request.items().stream().collect(Collectors.toMap(ReturnReceiveRequest.Item::orderItemId, Function.identity(), (left, right) -> { throw new BusinessConflictException("Không được lặp sản phẩm."); }));
        for (ReturnCaseItemEntity item : items.findByReturnCaseIdOrderByIdAsc(caseId)) {
            int quantity = received.getOrDefault(item.getOrderItemId(), new ReturnReceiveRequest.Item(item.getOrderItemId(), 0)).receivedQuantity();
            if (quantity < 0 || quantity > item.getApprovedQuantity()) throw new BusinessConflictException("Số lượng nhận vượt số lượng đã duyệt.");
            item.setReceivedQuantity(quantity); items.save(item);
        }
        if(items.findByReturnCaseIdOrderByIdAsc(caseId).stream().noneMatch(i->i.getReceivedQuantity()>0))throw new BusinessConflictException("Phải nhận ít nhất một sản phẩm.");
        transition(value, ReturnCaseStatus.RECEIVED, actorId, requestId, "{}");
        return response(value);
    }

    @Transactional
    public ReturnCaseResponse inspect(UUID actorId, UUID caseId, ReturnInspectionRequest request, String requestId) {
        ReturnCaseEntity value = locked(actorId, caseId);
        if (value.getStatus() != ReturnCaseStatus.RECEIVED) throw new BusinessConflictException("Case chưa sẵn sàng kiểm tra.");
        if(request==null||request.accept()==null||request.reason()!=null&&request.reason().length()>1000||request.items()==null||request.items().stream().anyMatch(i->i.disposition()==null||i.receivedQuantity()<0))throw new BusinessConflictException("Thông tin kiểm tra không hợp lệ.");
        Map<UUID, ReturnInspectionRequest.Item> inspected = request.items().stream().collect(Collectors.toMap(ReturnInspectionRequest.Item::orderItemId, Function.identity(), (left, right) -> { throw new BusinessConflictException("Không được lặp sản phẩm."); }));
        for (ReturnCaseItemEntity item : items.findByReturnCaseIdOrderByIdAsc(caseId)) {
            ReturnInspectionRequest.Item inspection = inspected.get(item.getOrderItemId());
            if (inspection == null || inspection.receivedQuantity() != item.getReceivedQuantity()) throw new BusinessConflictException("Inspection phải khớp quantity đã nhận.");
            item.setInspectionResult(inspection.disposition() == StockDisposition.RESTOCK ? com.utewedding.app.domain.InspectionResult.ACCEPTED : inspection.disposition() == StockDisposition.DAMAGED ? com.utewedding.app.domain.InspectionResult.DAMAGED : com.utewedding.app.domain.InspectionResult.NO_RESTOCK);
            item.setStockDisposition(inspection.disposition()); items.save(item);
            releaseReservation(item.getOrderItemId(), item.getApprovedQuantity() - item.getReceivedQuantity());
        }
        transition(value, ReturnCaseStatus.INSPECTED, actorId, requestId, "{}");
        if (Boolean.FALSE.equals(request.accept())) {
            if (request.reason() == null || request.reason().isBlank()) throw new BusinessConflictException("Từ chối inspection phải có lý do.");
            value.setRejectionReason(request.reason().trim()); value.setDisputeDeadline(Instant.now().plus(ReturnPolicy.DISPUTE_WINDOW));
            transition(value, ReturnCaseStatus.REJECTED, actorId, requestId, "{\"decision\":\"REJECTED\"}");
        } else transition(value, ReturnCaseStatus.RESOLUTION_PENDING, actorId, requestId, "{\"decision\":\"ACCEPTED\"}");
        return response(value);
    }

    @Transactional
    public ReturnCaseResponse ship(UUID customerId, UUID caseId, ReturnShipmentRequest request, String requestId) {
        ReturnCaseEntity value = cases.findByIdForUpdate(caseId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu trả hàng."));
        if (!customerId.equals(value.getCustomerId())) throw new org.springframework.security.access.AccessDeniedException("Không có quyền với case này.");
        if (value.getStatus() != ReturnCaseStatus.AWAITING_RETURN) throw new BusinessConflictException("Case chưa chờ khách gửi hàng.");
        if (value.getShipDeadline() != null && Instant.now().isAfter(value.getShipDeadline())) throw new BusinessConflictException("Đã quá hạn gửi hàng.");
        ReturnShipmentEntity shipment = shipments.findByReturnCaseIdAndDirection(caseId, ReturnShipmentDirection.CUSTOMER_TO_SHOP)
                .orElseThrow(() -> new BusinessConflictException("Case chưa tạo reverse shipment."));
        if (shipment.getStatus() != ReturnShipmentStatus.CREATED) throw new BusinessConflictException("Shipment đã được khai báo.");
        if(request==null||request.carrier()==null||request.carrier().isBlank()||request.carrier().length()>120||request.trackingNumber()==null||request.trackingNumber().isBlank()||request.trackingNumber().length()>120)throw new BusinessConflictException("Vận đơn không hợp lệ.");
        shipment.setCarrier(request.carrier().trim()); shipment.setTrackingNumber(request.trackingNumber().trim()); shipment.setStatus(ReturnShipmentStatus.IN_TRANSIT); shipments.save(shipment);
        transition(value, ReturnCaseStatus.RETURN_IN_TRANSIT, customerId, requestId, "{}");
        return response(value);
    }

    @Transactional
    public void markShipmentDelivered(UUID actorId, UUID caseId) {
        ReturnCaseEntity value = locked(actorId, caseId);
        if (value.getStatus() != ReturnCaseStatus.RETURN_IN_TRANSIT) throw new BusinessConflictException("Case chưa ở trạng thái đang vận chuyển.");
        ReturnShipmentEntity shipment = shipments.findByReturnCaseIdAndDirection(caseId, ReturnShipmentDirection.CUSTOMER_TO_SHOP)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy reverse shipment."));
        if (shipment.getStatus() != ReturnShipmentStatus.IN_TRANSIT) throw new BusinessConflictException("Shipment chưa đang vận chuyển.");
        shipment.setStatus(ReturnShipmentStatus.DELIVERED); shipments.save(shipment);
    }

    @Transactional
    public ReturnCaseResponse openDispute(UUID customerId, UUID caseId, ReturnDisputeRequest request, String requestId) {
        ReturnCaseEntity value = cases.findByIdForUpdate(caseId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu trả hàng."));
        if (!customerId.equals(value.getCustomerId())) throw new org.springframework.security.access.AccessDeniedException("Không có quyền với case này.");
        if (value.getStatus() != ReturnCaseStatus.REJECTED || value.getDisputeDeadline() == null || Instant.now().isAfter(value.getDisputeDeadline())) throw new BusinessConflictException("Case không còn trong dispute window.");
        if(request==null||request.reason()==null||request.reason().isBlank()||request.reason().length()>1000)throw new BusinessConflictException("Cần lý do tranh chấp, tối đa 1000 ký tự.");
        ReturnDisputeEntity dispute = disputes.saveAndFlush(new ReturnDisputeEntity(caseId, customerId, request.reason().trim()));
        transition(value, ReturnCaseStatus.DISPUTED, customerId, requestId, "{}");
        return response(value);
    }

    @Transactional
    public ReturnCaseResponse decideDispute(UUID actorId, UUID caseId, UUID disputeId,
                                            ReturnDisputeDecisionRequest request) {
        UserEntity actor = users.findById(actorId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        if (actor.getStatus()!=com.utewedding.app.domain.UserStatus.ACTIVE || (actor.getRole() != UserRole.MANAGER && actor.getRole() != UserRole.ADMIN && actor.getRole() != UserRole.SUPER_ADMIN)) throw new org.springframework.security.access.AccessDeniedException("Chỉ staff được quyết định dispute.");
        ReturnCaseEntity value = cases.findByIdForUpdate(caseId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu trả hàng."));
        ReturnDisputeEntity dispute = disputes.findById(disputeId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy dispute."));
        if (!caseId.equals(dispute.getReturnCaseId()) || dispute.getStatus() != ReturnDisputeStatus.OPEN || value.getStatus() != ReturnCaseStatus.DISPUTED) throw new BusinessConflictException("Dispute không còn xử lý được.");
        if(request.decision()==null||request.decision().isBlank()||request.decision().length()>1000)throw new BusinessConflictException("Cần lý do quyết định tranh chấp.");
        dispute.setDecision(request.decision().trim()); dispute.setDecisionActor(actorId); dispute.setDecidedAt(Instant.now());
        if (request.upheld()) {
            dispute.setStatus(ReturnDisputeStatus.UPHELD); transition(value, ReturnCaseStatus.CLOSED, actorId, null, "{\"decision\":\"UPHELD\"}");
        } else {
            dispute.setStatus(ReturnDisputeStatus.OVERTURNED);
            boolean alreadyReceived=items.findByReturnCaseIdOrderByIdAsc(caseId).stream().anyMatch(i->i.getInspectionResult()!=null);
            transition(value,alreadyReceived?ReturnCaseStatus.RESOLUTION_PENDING:ReturnCaseStatus.APPROVED,actorId,null,"{\"decision\":\"OVERTURNED\"}");
            for(var item:items.findByReturnCaseIdOrderByIdAsc(caseId)){item.setApprovedQuantity(item.getInspectionResult()!=null?item.getReceivedQuantity():item.getRequestedQuantity());items.save(item);}
            if (alreadyReceived) { /* Reuse inspected quantities; do not ask the customer to ship twice. */ }
            else if (value.isReturnRequired()) {value.setShipDeadline(Instant.now().plus(ReturnPolicy.SHIP_WINDOW));shipments.findByReturnCaseIdAndDirection(caseId,ReturnShipmentDirection.CUSTOMER_TO_SHOP).orElseGet(()->createShipment(value,ReturnShipmentDirection.CUSTOMER_TO_SHOP));transition(value, ReturnCaseStatus.AWAITING_RETURN, actorId, null, "{}");}
            else transition(value, ReturnCaseStatus.RESOLUTION_PENDING, actorId, null, "{}");
        }
        disputes.save(dispute);
        return response(value);
    }

    @Transactional public void close(UUID actor,UUID caseId,String requestId){var user=users.findById(actor).orElseThrow();if(user.getStatus()!=com.utewedding.app.domain.UserStatus.ACTIVE||!java.util.Set.of(UserRole.MANAGER,UserRole.ADMIN,UserRole.SUPER_ADMIN).contains(user.getRole()))throw new org.springframework.security.access.AccessDeniedException("Chỉ staff được đóng hồ sơ.");var value=cases.findByIdForUpdate(caseId).orElseThrow();if(value.getStatus()!=ReturnCaseStatus.RESOLVED)throw new BusinessConflictException("Chỉ đóng hồ sơ đã xử lý xong.");transition(value,ReturnCaseStatus.CLOSED,actor,requestId,"{}");}

    private ReturnCaseEntity locked(UUID actorId, UUID caseId) {
        ReturnCaseEntity value = cases.findByIdForUpdate(caseId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu trả hàng."));
        UserEntity actor = users.findById(actorId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        boolean staff = actor.getRole() == UserRole.MANAGER || actor.getRole() == UserRole.ADMIN || actor.getRole() == UserRole.SUPER_ADMIN;
        if (actor.getStatus()!=com.utewedding.app.domain.UserStatus.ACTIVE || !staff) throw new org.springframework.security.access.AccessDeniedException("Chỉ nhân viên quản trị được xử lý case này.");
        return value;
    }

    private ReturnShipmentEntity createShipment(ReturnCaseEntity returnCase, ReturnShipmentDirection direction) {
        ReturnShipmentEntity shipment = new ReturnShipmentEntity(returnCase.getId(), direction);
        shipment.setShopId(returnCase.getShopId());
        if (fulfillmentShipments != null) {
            fulfillmentShipments.findFirstByFulfillmentIdAndProviderCodeOrderByCreatedAtDesc(
                    returnCase.getFulfillmentId(), "GHN").filter(existing ->
                    returnCase.getShopId().equals(existing.getShopId())
                            && existing.getProviderAccountId() != null
                            && existing.getProviderContextSnapshot() != null
                            && !existing.getProviderContextSnapshot().isBlank())
                    .ifPresent(existing -> {
                        shipment.setProviderAccountId(existing.getProviderAccountId());
                        shipment.setProviderContextSnapshot(existing.getProviderContextSnapshot());
                        shipment.setCarrier("GHN");
                    });
        }
        return shipments.save(shipment);
    }

    private void releaseReservation(UUID orderItemId, int quantity) {
        if (quantity <= 0) return;
        int updated = entityManager.createNativeQuery("UPDATE order_items SET return_reserved_quantity = return_reserved_quantity - :quantity WHERE id = :id AND return_reserved_quantity >= :quantity")
                .setParameter("quantity", quantity).setParameter("id", orderItemId).executeUpdate();
        if (updated != 1) throw new BusinessConflictException("Không thể giải phóng reservation.");
    }

    private void transition(ReturnCaseEntity value, ReturnCaseStatus target, UUID actorId, String requestId, String metadata) {
        ReturnCaseStatus previous = value.getStatus(); value.transitionTo(target); cases.save(value);
        history.save(new com.utewedding.app.domain.entity.ReturnCaseHistoryEntity(value.getId(), actorId, actorRole(actorId), previous.name(), target.name(), requestId, metadata));
        if (events != null) events.publishEvent(
                new com.utewedding.app.event.AdminAttentionChangedEvent("ReturnCase", value.getId()));
    }

    private String actorRole(UUID actorId) {
        return actorId == null ? "SYSTEM"
                : users.findById(actorId).map(user -> user.getRole().name()).orElse("UNKNOWN");
    }

    private ReturnCaseResponse response(ReturnCaseEntity value) {
        return new ReturnCaseResponse(value.getId(), value.getCaseNumber(), value.getOrderId(), value.getFulfillmentId(), value.getStatus(), value.getReason(), value.getRequestDeadline(), value.getShipDeadline(), value.getDisputeDeadline(), items.findByReturnCaseIdOrderByIdAsc(value.getId()).stream().map(item -> new ReturnCaseResponse.Item(item.getOrderItemId(), item.getRequestedQuantity(), item.getApprovedQuantity(), item.getReceivedQuantity(), item.getResolvedQuantity(), item.getRefundAmount())).toList());
    }
}
