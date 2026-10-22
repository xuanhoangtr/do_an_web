package com.utewedding.app.service.impl;

import com.utewedding.app.domain.*;
import com.utewedding.app.domain.entity.*;
import com.utewedding.app.dto.request.PaymentRequest;
import com.utewedding.app.exception.*;
import com.utewedding.app.repository.*;
import com.utewedding.app.service.OrderService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import java.time.Instant;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable intent followed by a locked, idempotent payment attempt. Failure is recorded after rollback. */
@Service
public class ReturnResolutionService {
    private final ReturnCaseRepository cases;
    private final ReturnCaseItemRepository items;
    private final ReturnResolutionRepository resolutions;
    private final ReturnResolutionItemRepository lines;
    private final ReturnCaseHistoryRepository history;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final UserRepository users;
    private final OrderService payments;
    private final AuditLogPort audit;
    private final TransactionTemplate transactions;
    @org.springframework.beans.factory.annotation.Autowired private ReturnInventoryService inventory;
    @org.springframework.beans.factory.annotation.Autowired private com.utewedding.app.repository.PaymentProviderTransactionRepository providerTransactions;
    @org.springframework.beans.factory.annotation.Autowired private OutboxService outbox;
    @org.springframework.beans.factory.annotation.Autowired private com.utewedding.app.service.payment.PaymentIntegrityService integrity;
    @org.springframework.beans.factory.annotation.Autowired private PaymentRepository paymentHistory;
    private org.springframework.context.ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired
    private ReturnFinanceService returnFinance;

    public ReturnResolutionService(ReturnCaseRepository cases, ReturnCaseItemRepository items,
            ReturnResolutionRepository resolutions, ReturnResolutionItemRepository lines, ReturnCaseHistoryRepository history,
            OrderRepository orders, OrderItemRepository orderItems, UserRepository users, OrderService payments,
            AuditLogPort audit, PlatformTransactionManager manager) {
        this.cases=cases; this.items=items; this.resolutions=resolutions; this.lines=lines; this.history=history;
        this.orders=orders; this.orderItems=orderItems; this.users=users; this.payments=payments;
        this.audit=audit; this.transactions=new TransactionTemplate(manager);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachAttentionEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    public ReturnResolutionEntity refund(UUID actor, UUID caseId, String key, String manualReference, RequestContext context) {
        requireAdmin(actor);
        if (key == null || key.isBlank() || key.length()>190) throw new BusinessConflictException("Thiếu khóa xác nhận biểu mẫu.");
        UUID orderId = cases.findById(caseId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu trả hàng.")).getOrderId();
        String hash = digest(caseId + "|REFUND|" + (manualReference == null ? "" : manualReference.trim()));
        UUID resolutionId = transactions.execute(tx -> {
            var order = orders.findByIdForUpdate(orderId).orElseThrow(); // Global lock order: order, case, resolution.
            var c = cases.findByIdForUpdate(caseId).orElseThrow();
            var prior = resolutions.findByIdempotencyKey(key).orElse(null);
            if (prior != null) {
                if (!caseId.equals(prior.getReturnCaseId()) || !hash.equals(prior.getRequestHash()))
                    throw new BusinessConflictException("Khóa đã được dùng cho nội dung khác.");
                return prior.getId();
            }
            if (c.getStatus()!=ReturnCaseStatus.RESOLUTION_PENDING || resolutions.findByReturnCaseId(caseId).isPresent())
                throw new BusinessConflictException("Yêu cầu không chờ quyết định hoặc đã có resolution. Dùng thử lại resolution hiện tại.");
            var allocations = new ArrayList<Allocation>();
            long amount=0;
            for (var item : items.findByReturnCaseIdOrderByIdAsc(caseId)) {
                int quantity=(c.isReturnRequired()?item.getReceivedQuantity():item.getApprovedQuantity())-item.getResolvedQuantity();
                if (quantity<=0) continue;
                var purchased=orderItems.findById(item.getOrderItemId()).orElseThrow();
                int before=Math.toIntExact(lines.allocatedQuantity(purchased.getId()));
                long refund=ReturnRefundAllocation.cumulativeDelta(purchased.getNetPaidAmount(),before,Math.addExact(before,quantity),purchased.getQuantity());
                amount=Math.addExact(amount,refund); allocations.add(new Allocation(item.getId(),quantity,refund));
            }
            if (amount<=0) throw new BusinessConflictException("Không có số lượng/số tiền có thể hoàn.");
            boolean manual=integrity.onlineProvider(order)==null;
            if (manual && (manualReference==null || manualReference.isBlank() || manualReference.length()>190))
                throw new BusinessConflictException("Cần mã chuyển khoản hoàn tiền thực tế trước khi xác nhận.");
            if(manual && (resolutions.existsByManualReference(manualReference.trim())
                    || paymentHistory.findByProviderTransactionId(manualReference.trim()).isPresent()))
                throw new BusinessConflictException("Mã chuyển khoản này đã được dùng cho một lần hoàn tiền khác.");
            var resolution=resolutions.saveAndFlush(new ReturnResolutionEntity(caseId,ReturnResolutionType.REFUND,key,hash,amount));
            resolution.setManualReference(manual?manualReference.trim():null);
            for (var allocation:allocations) lines.save(new ReturnResolutionItemEntity(resolution.getId(),allocation.item(),allocation.quantity(),allocation.amount()));
            transition(c,ReturnCaseStatus.REFUND_PENDING,actor,context);
            return resolution.getId();
        });
        return attempt(actor,orderId,caseId,resolutionId,manualReference,context);
    }

    public ReturnResolutionEntity retry(UUID actor, UUID caseId, RequestContext context) {
        requireAdmin(actor);
        var c=cases.findById(caseId).orElseThrow();
        var resolution=resolutions.findByReturnCaseId(caseId).orElseThrow();
        if(resolution.getType()!=ReturnResolutionType.REFUND)
            throw new BusinessConflictException("Resolution này không phải hoàn tiền.");
        // Retry preserves the original request and provider key, including a manually confirmed reference.
        if (resolution.getStatus()==ReturnResolutionStatus.SUCCEEDED) return resolution;
        return attempt(actor,c.getOrderId(),caseId,resolution.getId(),resolution.getManualReference(),context);
    }

    private ReturnResolutionEntity attempt(UUID actor, UUID orderId, UUID caseId, UUID resolutionId,
                                             String manualReference, RequestContext context) {
        try {
            RefundAttemptPlan plan = transactions.execute(tx -> {
                var order=orders.findByIdForUpdate(orderId).orElseThrow();
                var c=cases.findByIdForUpdate(caseId).orElseThrow();
                var r=resolutions.findByIdForUpdate(resolutionId).orElseThrow();
                if(r.getStatus()==ReturnResolutionStatus.SUCCEEDED) return RefundAttemptPlan.done();
                if ((r.getStatus() == ReturnResolutionStatus.PROCESSING
                        || r.getStatus() == ReturnResolutionStatus.MANUAL_REFUND_PENDING)
                        && r.getProcessedAt() != null
                        && r.getProcessedAt().isAfter(Instant.now().minusSeconds(120))) {
                    return RefundAttemptPlan.inProgress();
                }
                if(c.getStatus()==ReturnCaseStatus.REFUND_FAILED) transition(c,ReturnCaseStatus.REFUND_PENDING,actor,context);
                if(c.getStatus()!=ReturnCaseStatus.REFUND_PENDING) throw new BusinessConflictException("Không thể thử lại ở trạng thái hiện tại.");
                String provider=integrity.onlineProvider(order);
                boolean online=provider!=null;
                if(!online && (manualReference==null || manualReference.isBlank()))
                    throw new BusinessConflictException("Hoàn tiền thủ công cần xác nhận đã chuyển và mã tham chiếu thực tế.");
                returnFinance.ensureRefundHold(order, c, r, lines.findByResolutionId(r.getId()));
                r.setManualReference(online?null:manualReference.trim());
                String providerKey = online ? providerRefundKey(r.getId()) : null;
                if (online) providerTransactions.findByIdempotencyKey(providerKey).ifPresent(transaction -> {
                    transaction.setReturnResolutionId(r.getId());
                    r.setPaymentTransactionId(transaction.getId());
                    providerTransactions.save(transaction);
                });
                r.setStatus(ReturnResolutionStatus.PROCESSING);
                r.setProcessedAt(Instant.now());
                resolutions.save(r);
                return new RefundAttemptPlan(false, online, provider, r.getAmount(),
                        online ? providerKey : null, online ? null : manualReference.trim(), false);
            });
            if (plan.completed()) return resolutions.findById(resolutionId).orElseThrow();
            if (plan.busy()) return resolutions.findById(resolutionId).orElseThrow();

            // The provider call runs after the preparation transaction committed, with no order row lock held.
            payments.refundReturn(actor,orderId,new PaymentRequest(plan.online()?plan.providerKey():plan.manualReference(),
                    plan.online()?plan.provider():"MANUAL_REFUND",plan.amount()),context);

            return finalizeSuccessfulRefund(actor, orderId, caseId, resolutionId,
                    plan.online() ? plan.providerKey() : null, context);
        } catch (RuntimeException failure) {
            boolean manual = manualReference != null && !manualReference.isBlank();
            if (manual) {
                PaymentEntity recorded = paymentHistory.findByProviderTransactionId(manualReference.trim()).orElse(null);
                ReturnResolutionEntity current = resolutions.findById(resolutionId).orElseThrow();
                if (recorded != null && orderId.equals(recorded.getOrderId())
                        && recorded.getStatus() == PaymentStatus.REFUNDED
                        && recorded.getAmount() == current.getAmount()) {
                    return finalizeSuccessfulRefund(actor, orderId, caseId, resolutionId, null, context);
                }
            }
            boolean ambiguous = manual
                    ? paymentHistory.findByProviderTransactionId(manualReference.trim()).isEmpty()
                    : refundOutcomeUnresolved(resolutionId);
            transactions.executeWithoutResult(tx -> {
                var order = orders.findByIdForUpdate(orderId).orElseThrow();
                var c=cases.findByIdForUpdate(caseId).orElseThrow();
                var r=resolutions.findByIdForUpdate(resolutionId).orElseThrow();
                if(r.getStatus()!=ReturnResolutionStatus.SUCCEEDED) {
                    if (ambiguous) {
                        r.setStatus(manualReference == null || manualReference.isBlank()
                                ? ReturnResolutionStatus.PROCESSING : ReturnResolutionStatus.MANUAL_REFUND_PENDING);
                        r.setProcessedAt(Instant.now());
                        resolutions.save(r);
                        audit.record("PAYMENT_STATUS_CHANGED",actor,"ReturnResolution",resolutionId,
                                Map.of("status", r.getStatus().name(), "reconciliationRequired", true),context);
                    } else {
                        r.setStatus(ReturnResolutionStatus.FAILED);
                        if(c.getStatus()==ReturnCaseStatus.REFUND_PENDING)
                            transition(c,ReturnCaseStatus.REFUND_FAILED,actor,context);
                        returnFinance.releaseRefundHold(order, c, r, actor);
                        resolutions.save(r);
                        audit.record("PAYMENT_STATUS_CHANGED",actor,"ReturnResolution",resolutionId,
                                Map.of("status","FAILED"),context);
                    }
                }
            });
            throw new BusinessConflictException(ambiguous
                    ? "Kết quả hoàn tiền chưa được xác nhận. Giữ nguyên khoản tài chính và thử lại cùng resolution sau khi đối soát."
                    : "Cổng xác nhận lần hoàn chưa thành công. Khoản giữ đã được giải phóng; có thể thử lại resolution này.");
        }
    }

    /** Finalizes a provider refund that the payment-intent worker has positively reconciled. */
    public int reconcileSucceededProviderRefunds() {
        int finalized = 0;
        RequestContext systemContext = new RequestContext(null, null, "refund-intent-reconciler", "SYSTEM");
        for (ReturnResolutionEntity resolution : resolutions.findByTypeAndStatusOrderByCreatedAtAsc(
                ReturnResolutionType.REFUND, ReturnResolutionStatus.PROCESSING)) {
            List<PaymentProviderTransactionEntity> attempts = providerTransactions
                    .findByReturnResolutionIdOrderByCreatedAtAsc(resolution.getId());
            PaymentProviderTransactionEntity succeeded = attempts.stream()
                    .filter(attempt -> attempt.getStatus() == ProviderTransactionStatus.SUCCEEDED)
                    .reduce((first, second) -> second).orElse(null);
            if (succeeded == null) continue;
            ReturnCaseEntity returnCase = cases.findById(resolution.getReturnCaseId()).orElse(null);
            if (returnCase == null) continue;
            try {
                ReturnResolutionEntity result = finalizeSuccessfulRefund(null, returnCase.getOrderId(),
                        returnCase.getId(), resolution.getId(), succeeded.getIdempotencyKey(), systemContext);
                if (result.getStatus() == ReturnResolutionStatus.SUCCEEDED) finalized++;
            } catch (RuntimeException failure) {
                // Keep the finance hold active; a later reconciliation run can retry this finalization.
                audit.record("PAYMENT_STATUS_CHANGED", null, "ReturnResolution", resolution.getId(),
                        Map.of("status", "PROCESSING", "reconciliationRequired", true), systemContext);
            }
        }
        return finalized;
    }

    private ReturnResolutionEntity finalizeSuccessfulRefund(UUID actor, UUID orderId, UUID caseId,
                                                             UUID resolutionId, String providerKey,
                                                             RequestContext context) {
        return transactions.execute(tx -> {
            var order = orders.findByIdForUpdate(orderId).orElseThrow();
            var c = cases.findByIdForUpdate(caseId).orElseThrow();
            var r = resolutions.findByIdForUpdate(resolutionId).orElseThrow();
            if (r.getStatus() == ReturnResolutionStatus.SUCCEEDED) return r;
            if (c.getStatus() != ReturnCaseStatus.REFUND_PENDING) {
                throw new BusinessConflictException("Hồ sơ trả hàng đã thay đổi trong lúc xử lý hoàn tiền.");
            }
            if (providerKey != null) providerTransactions.findByIdempotencyKey(providerKey).ifPresent(transaction -> {
                transaction.setReturnResolutionId(r.getId());
                r.setPaymentTransactionId(transaction.getId());
                providerTransactions.save(transaction);
            });
            returnFinance.recordRefund(order, c, r, lines.findByResolutionId(r.getId()), actor);
            for (var allocation : lines.findByResolutionId(r.getId())) {
                var item = items.findById(allocation.getReturnCaseItemId()).orElseThrow();
                var purchased = orderItems.findById(item.getOrderItemId()).orElseThrow();
                if (purchased.getReturnReservedQuantity() < allocation.getQuantity()) {
                    throw new BusinessConflictException("Số lượng giữ chỗ không khớp.");
                }
                purchased.setReturnReservedQuantity(purchased.getReturnReservedQuantity() - allocation.getQuantity());
                purchased.setReturnResolvedQuantity(Math.addExact(purchased.getReturnResolvedQuantity(), allocation.getQuantity()));
                item.setResolvedQuantity(item.getResolvedQuantity() + allocation.getQuantity());
                item.setRefundAmount(item.getRefundAmount() + allocation.getAmount());
                item.setResolutionType(ReturnItemResolution.REFUND);
                orderItems.save(purchased);
                items.save(item);
            }
            inventory.apply(caseId, r.getId(), lines.findByResolutionId(r.getId()), false);
            r.setStatus(ReturnResolutionStatus.SUCCEEDED);
            r.setProcessedAt(Instant.now());
            transition(c, ReturnCaseStatus.RESOLVED, actor, context);
            audit.record("PAYMENT_STATUS_CHANGED", actor, "ReturnResolution", r.getId(),
                    Map.of("amount", r.getAmount(), "status", "SUCCEEDED"), context);
            outbox.enqueue("RETURN_RESOLVED", "ReturnResolution", r.getId(), "return-resolved:" + r.getId(),
                    c.getCustomerId() + "|" + c.getId(), context);
            return resolutions.save(r);
        });
    }

    private boolean refundOutcomeUnresolved(UUID resolutionId) {
        if (providerTransactions == null) return false;
        List<PaymentProviderTransactionEntity> attempts = providerTransactions
                .findByReturnResolutionIdOrderByCreatedAtAsc(resolutionId);
        if (attempts.isEmpty()) return false;
        ProviderTransactionStatus status = attempts.get(attempts.size() - 1).getStatus();
        return status == ProviderTransactionStatus.PENDING
                || status == ProviderTransactionStatus.PROCESSING
                || status == ProviderTransactionStatus.SUCCEEDED;
    }

    private void requireAdmin(UUID actor) {
        var user=users.findById(actor).orElseThrow(() -> new AccessDeniedException("Không có quyền hoàn tiền."));
        if(user.getStatus()!=UserStatus.ACTIVE || !(user.getRole()==UserRole.ADMIN || user.getRole()==UserRole.SUPER_ADMIN))
            throw new AccessDeniedException("Chỉ ADMIN/SUPER_ADMIN được hoàn tiền.");
    }

    private String providerRefundKey(UUID resolutionId) {
        String firstKey = "return-" + resolutionId;
        List<PaymentProviderTransactionEntity> attempts = new ArrayList<>(
                providerTransactions.findByReturnResolutionIdOrderByCreatedAtAsc(resolutionId));
        providerTransactions.findByIdempotencyKey(firstKey).ifPresent(existing -> {
            existing.setReturnResolutionId(resolutionId);
            providerTransactions.save(existing);
            if (attempts.stream().noneMatch(row -> row.getId().equals(existing.getId()))) attempts.add(existing);
        });
        if (attempts.isEmpty()) return firstKey;
        PaymentProviderTransactionEntity last = attempts.get(attempts.size() - 1);
        if (last.getStatus() == ProviderTransactionStatus.PENDING
                || last.getStatus() == ProviderTransactionStatus.PROCESSING
                || last.getStatus() == ProviderTransactionStatus.SUCCEEDED) {
            return last.getIdempotencyKey();
        }
        return firstKey + "-" + (attempts.size() + 1);
    }

    private void transition(ReturnCaseEntity c,ReturnCaseStatus target,UUID actor,RequestContext context) {
        var before=c.getStatus(); c.transitionTo(target); cases.save(c);
        String actorRole = actor == null ? "SYSTEM" : users.findById(actor).orElseThrow().getRole().name();
        history.save(new ReturnCaseHistoryEntity(c.getId(),actor,actorRole,before.name(),target.name(),context==null?null:context.requestId(),"{}"));
        if (events != null) events.publishEvent(
                new com.utewedding.app.event.AdminAttentionChangedEvent("ReturnCase", c.getId()));
    }
    private static String digest(String text) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private record Allocation(UUID item,int quantity,long amount){}
    private record RefundAttemptPlan(boolean completed, boolean online, String provider,
                                     long amount, String providerKey, String manualReference, boolean busy) {
        static RefundAttemptPlan done() { return new RefundAttemptPlan(true, false, null, 0, null, null, false); }
        static RefundAttemptPlan inProgress() {
            return new RefundAttemptPlan(false, false, null, 0, null, null, true);
        }
    }
}
