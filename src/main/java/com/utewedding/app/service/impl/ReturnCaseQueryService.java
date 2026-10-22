package com.utewedding.app.service.impl;

import com.utewedding.app.domain.*;
import com.utewedding.app.domain.entity.*;
import com.utewedding.app.exception.*;
import com.utewedding.app.repository.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class ReturnCaseQueryService {
    private final ReturnCaseRepository cases; private final ReturnCaseItemRepository items;
    private final ReturnCaseHistoryRepository history; private final ReturnShipmentRepository shipments;
    private final ReturnDisputeRepository disputes; private final ReturnResolutionRepository resolutions;
    private final OrderRepository orders; private final OrderFulfillmentRepository fulfillments;
    private final OrderFulfillmentItemRepository links; private final OrderItemRepository purchased;
    private final OperationsQueryService staff;
    public ReturnCaseQueryService(ReturnCaseRepository cases,ReturnCaseItemRepository items,ReturnCaseHistoryRepository history,
            ReturnShipmentRepository shipments,ReturnDisputeRepository disputes,ReturnResolutionRepository resolutions,
            OrderRepository orders,OrderFulfillmentRepository fulfillments,OrderFulfillmentItemRepository links,
            OrderItemRepository purchased,OperationsQueryService staff) {
        this.cases=cases;this.items=items;this.history=history;this.shipments=shipments;this.disputes=disputes;
        this.resolutions=resolutions;this.orders=orders;this.fulfillments=fulfillments;this.links=links;this.purchased=purchased;
        this.staff=staff;
    }
    public Page<ReturnCaseEntity> list(UUID actor,String scope,ReturnCaseStatus status,Instant from,Instant to,String q,boolean disputed,int page) {
        authorize(actor,scope);
        return cases.findAll((root,query,builder)-> {
            var filters=new ArrayList<jakarta.persistence.criteria.Predicate>();
            if(scope.equals("customer"))filters.add(builder.equal(root.get("customerId"),actor));
            if(status!=null)filters.add(builder.equal(root.get("status"),status));
            if(from!=null)filters.add(builder.greaterThanOrEqualTo(root.get("createdAt"),from));
            if(to!=null)filters.add(builder.lessThan(root.get("createdAt"),to));
            if(q!=null&&!q.isBlank())filters.add(builder.like(root.get("caseNumber"),"%"+q.trim()+"%"));
            if(disputed){var sub=query.subquery(UUID.class);var d=sub.from(ReturnDisputeEntity.class);sub.select(d.get("id")).where(builder.equal(d.get("returnCaseId"),root.get("id")));filters.add(builder.exists(sub));}
            return builder.and(filters.toArray(jakarta.persistence.criteria.Predicate[]::new));
        },PageRequest.of(Math.max(page,0),20,Sort.by("createdAt").descending().and(Sort.by("id"))));
    }
    public Detail detail(UUID actor,String scope,UUID id) {
        authorize(actor,scope);
        var c=(scope.equals("customer")?cases.findByIdAndCustomerId(id,actor):cases.findById(id))
                .orElseThrow(()->new ResourceNotFoundException("Không tìm thấy yêu cầu trả hàng."));
        var rows=items.findByReturnCaseIdOrderByIdAsc(id);
        long maximum=0;
        for(var row:rows) {
            var original=purchased.findById(row.getOrderItemId()).orElseThrow();
            int qty=(c.isReturnRequired()?row.getReceivedQuantity():row.getApprovedQuantity())-row.getResolvedQuantity();
            int ownOutstandingReservation=Math.max(0,row.getApprovedQuantity()-row.getResolvedQuantity());
            int otherReservations=Math.max(0,original.getReturnReservedQuantity()-ownOutstandingReservation);
            int alreadyAllocated=original.getReturnResolvedQuantity()+otherReservations;
            if(qty>0)maximum=Math.addExact(maximum,ReturnRefundAllocation.cumulativeDelta(original.getNetPaidAmount(),alreadyAllocated,alreadyAllocated+qty,original.getQuantity()));
        }
        var allowed=new LinkedHashSet<String>();var status=c.getStatus();
        if(scope.equals("customer")) {
            if(status==ReturnCaseStatus.REQUESTED&&status.canTransitionTo(ReturnCaseStatus.CANCELLED))allowed.add("cancel");
            if(status==ReturnCaseStatus.AWAITING_RETURN&&c.getShipDeadline()!=null&&Instant.now().isBefore(c.getShipDeadline()))allowed.add("shipment");
            if(status==ReturnCaseStatus.REJECTED&&c.getDisputeDeadline()!=null&&Instant.now().isBefore(c.getDisputeDeadline()))allowed.add("disputes");
        } else {
            if(status==ReturnCaseStatus.REQUESTED)allowed.add("claim");
            if(status==ReturnCaseStatus.UNDER_REVIEW)allowed.add("review");
            if(status==ReturnCaseStatus.RETURN_IN_TRANSIT){allowed.add("receive");allowed.add("shipment/delivered");}
            if(status==ReturnCaseStatus.RECEIVED)allowed.add("inspect");
            if(scope.equals("admin")&&status==ReturnCaseStatus.DISPUTED)allowed.add("decision");
            if(scope.equals("admin")&&status==ReturnCaseStatus.RESOLUTION_PENDING){allowed.add("refund");allowed.add("exchange");}
            if(scope.equals("admin")&&status==ReturnCaseStatus.EXCHANGE_PENDING)allowed.add("exchange/delivered");
            if(scope.equals("admin")&&(status==ReturnCaseStatus.REFUND_FAILED||status==ReturnCaseStatus.REFUND_PENDING))allowed.add("retry");
            if(scope.equals("admin")&&status==ReturnCaseStatus.RESOLVED)allowed.add("close");
        }
        return new Detail(c,rows,history.findByReturnCaseIdOrderByCreatedAtAsc(id),shipments.findByReturnCaseId(id),
                disputes.findByReturnCaseId(id),resolutions.findByReturnCaseId(id).orElse(null),allowed,maximum);
    }
    private void authorize(UUID actor,String scope) {
        if(scope.equals("admin"))staff.requireStaff(actor);
        else if(!scope.equals("customer"))throw new AccessDeniedException("Không có quyền.");
    }
    public List<ReturnCaseEntity> existing(UUID customer,UUID order){return cases.findByOrderIdAndCustomerIdOrderByCreatedAtDesc(order,customer);}
    public List<Eligibility> eligibility(UUID customer,UUID orderId) {
        orders.findByIdAndUserId(orderId,customer).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        return fulfillments.findByOrderIdOrderByCreatedAtAsc(orderId).stream().map(f->{
            String reason=ineligible(f);var options=new ArrayList<ItemOption>();
            for(var link:links.findByFulfillmentIdOrderByIdAsc(f.getId())) {
                var i=purchased.findById(link.getOrderItemId()).orElseThrow();int remaining=i.getQuantity()-i.getReturnReservedQuantity()-i.getReturnResolvedQuantity();
                if(remaining>0)options.add(new ItemOption(i.getId(),i.getProductName(),remaining));
            }
            if(reason==null&&options.isEmpty())reason="Không còn số lượng có thể trả.";
            return new Eligibility(f.getId(),reason==null,reason,options);
        }).toList();
    }
    public static String ineligible(OrderFulfillmentEntity f) {
        if(f.getStatus()!=FulfillmentStatus.DELIVERED||f.getDeliveredAt()==null)return "Fulfillment chưa được giao.";
        if(!new ReturnPolicy().requestAllowed(f.getDeliveredAt(),Instant.now()))return "Đã quá hạn yêu cầu trả hàng.";
        return null;
    }
    public record ItemOption(UUID id,String name,int remaining){}
    public record Eligibility(UUID fulfillmentId,boolean eligible,String reason,List<ItemOption> items){}
    public record Detail(ReturnCaseEntity caseData,List<ReturnCaseItemEntity> items,List<ReturnCaseHistoryEntity> history,
                         List<ReturnShipmentEntity> shipments,List<ReturnDisputeEntity> disputes,ReturnResolutionEntity resolution,
                         Set<String> allowedActions,long maximumRefund){}
}
