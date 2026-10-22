package com.utewedding.app.service.impl;

import com.utewedding.app.domain.*;
import com.utewedding.app.domain.entity.*;
import com.utewedding.app.exception.*;
import com.utewedding.app.repository.*;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import java.time.Instant;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReturnExchangeService {
    private final ReturnCaseRepository cases;private final ReturnCaseItemRepository items;private final ReturnResolutionRepository resolutions;
    private final ReturnResolutionItemRepository lines;private final ReturnShipmentRepository shipments;private final OrderRepository orders;
    private final OrderItemRepository purchased;private final UserRepository users;private final ReturnInventoryService inventory;
    private final ReturnCaseHistoryRepository history;private final AuditLogPort audit;private final OutboxService outbox;
    private org.springframework.context.ApplicationEventPublisher events;
    public ReturnExchangeService(ReturnCaseRepository cases,ReturnCaseItemRepository items,ReturnResolutionRepository resolutions,ReturnResolutionItemRepository lines,ReturnShipmentRepository shipments,OrderRepository orders,OrderItemRepository purchased,UserRepository users,ReturnInventoryService inventory,ReturnCaseHistoryRepository history,AuditLogPort audit,OutboxService outbox){this.cases=cases;this.items=items;this.resolutions=resolutions;this.lines=lines;this.shipments=shipments;this.orders=orders;this.purchased=purchased;this.users=users;this.inventory=inventory;this.history=history;this.audit=audit;this.outbox=outbox;}
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachAttentionEvents(org.springframework.context.ApplicationEventPublisher events){this.events=events;}
    @Transactional public ReturnResolutionEntity exchange(UUID actor,UUID caseId,String key,String carrier,String tracking,RequestContext context){
        requireAdmin(actor);if(key==null||key.isBlank()||key.length()>190||carrier==null||carrier.isBlank()||carrier.length()>120||tracking==null||tracking.isBlank()||tracking.length()>120)throw new BusinessConflictException("Cần khóa biểu mẫu và vận đơn đổi hàng hợp lệ.");
        var snapshot=cases.findById(caseId).orElseThrow();orders.findByIdForUpdate(snapshot.getOrderId()).orElseThrow();var c=cases.findByIdForUpdate(caseId).orElseThrow();
        String hash=digest(caseId+"|EXCHANGE|"+carrier.trim()+"|"+tracking.trim());var prior=resolutions.findByIdempotencyKey(key).orElse(null);
        if(prior!=null){if(!caseId.equals(prior.getReturnCaseId())||!hash.equals(prior.getRequestHash()))throw new BusinessConflictException("Khóa đã dùng cho nội dung khác.");return prior;}
        if(c.getStatus()!=ReturnCaseStatus.RESOLUTION_PENDING||resolutions.findByReturnCaseId(caseId).isPresent())throw new BusinessConflictException("Yêu cầu không chờ quyết định đổi hàng.");
        var resolution=resolutions.saveAndFlush(new ReturnResolutionEntity(caseId,ReturnResolutionType.EXCHANGE,key,hash,0));var allocations=new ArrayList<ReturnResolutionItemEntity>();
        for(var item:items.findByReturnCaseIdOrderByIdAsc(caseId)){int quantity=(c.isReturnRequired()?item.getReceivedQuantity():item.getApprovedQuantity())-item.getResolvedQuantity();if(quantity>0)allocations.add(lines.save(new ReturnResolutionItemEntity(resolution.getId(),item.getId(),quantity,0)));}
        if(allocations.isEmpty())throw new BusinessConflictException("Không có số lượng để đổi.");
        inventory.apply(caseId,resolution.getId(),allocations,true);
        var shipment=new ReturnShipmentEntity(caseId,ReturnShipmentDirection.SHOP_TO_CUSTOMER);shipment.setShopId(c.getShopId());shipment.setCarrier(carrier.trim());shipment.setTrackingNumber(tracking.trim());shipment.setStatus(ReturnShipmentStatus.IN_TRANSIT);shipments.save(shipment);
        transition(c,ReturnCaseStatus.EXCHANGE_PENDING,actor,context);audit.record("FULFILLMENT_STATUS_CHANGED",actor,"ReturnResolution",resolution.getId(),Map.of("status","EXCHANGE_PENDING"),context);return resolution;
    }
    @Transactional public void delivered(UUID actor,UUID caseId,RequestContext context){requireAdmin(actor);var snapshot=cases.findById(caseId).orElseThrow();orders.findByIdForUpdate(snapshot.getOrderId()).orElseThrow();var c=cases.findByIdForUpdate(caseId).orElseThrow();var r=resolutions.findByReturnCaseId(caseId).orElseThrow();if(r.getType()!=ReturnResolutionType.EXCHANGE)throw new BusinessConflictException("Resolution không phải đổi hàng.");if(r.getStatus()==ReturnResolutionStatus.SUCCEEDED)return;if(c.getStatus()!=ReturnCaseStatus.EXCHANGE_PENDING)throw new BusinessConflictException("Không chờ giao hàng đổi.");for(var line:lines.findByResolutionId(r.getId())){var item=items.findById(line.getReturnCaseItemId()).orElseThrow();var original=purchased.findById(item.getOrderItemId()).orElseThrow();if(original.getReturnReservedQuantity()<line.getQuantity())throw new BusinessConflictException("Số lượng giữ chỗ không khớp.");original.setReturnReservedQuantity(original.getReturnReservedQuantity()-line.getQuantity());original.setReturnResolvedQuantity(original.getReturnResolvedQuantity()+line.getQuantity());item.setResolvedQuantity(item.getResolvedQuantity()+line.getQuantity());item.setResolutionType(ReturnItemResolution.EXCHANGE);purchased.save(original);items.save(item);}var shipment=shipments.findByReturnCaseIdAndDirection(caseId,ReturnShipmentDirection.SHOP_TO_CUSTOMER).orElseThrow();shipment.setStatus(ReturnShipmentStatus.DELIVERED);shipments.save(shipment);r.setStatus(ReturnResolutionStatus.SUCCEEDED);r.setProcessedAt(Instant.now());resolutions.save(r);transition(c,ReturnCaseStatus.RESOLVED,actor,context);outbox.enqueue("RETURN_RESOLVED","ReturnResolution",r.getId(),"return-resolved:"+r.getId(),c.getCustomerId()+"|"+c.getId(),context);}
    private void requireAdmin(UUID actor){var user=users.findById(actor).orElseThrow();if(user.getStatus()!=UserStatus.ACTIVE||!(user.getRole()==UserRole.ADMIN||user.getRole()==UserRole.SUPER_ADMIN))throw new AccessDeniedException("Chỉ ADMIN/SUPER_ADMIN được quyết định đổi hàng.");}
    private void transition(ReturnCaseEntity c,ReturnCaseStatus to,UUID actor,RequestContext context){var from=c.getStatus();c.transitionTo(to);cases.save(c);history.save(new ReturnCaseHistoryEntity(c.getId(),actor,users.findById(actor).orElseThrow().getRole().name(),from.name(),to.name(),context.requestId(),"{}"));if(events!=null)events.publishEvent(new com.utewedding.app.event.AdminAttentionChangedEvent("ReturnCase",c.getId()));}
    private static String digest(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
