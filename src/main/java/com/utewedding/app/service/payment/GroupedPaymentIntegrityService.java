package com.utewedding.app.service.payment;

import com.utewedding.app.domain.CheckoutGroupLifecycleStatus;
import com.utewedding.app.domain.CheckoutGroupPaymentStatus;
import com.utewedding.app.domain.AuditAction;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentAttemptStatus;
import com.utewedding.app.domain.PaymentCallbackOutcome;
import com.utewedding.app.domain.PaymentProviderEnvironment;
import com.utewedding.app.domain.PaymentReconciliationReason;
import com.utewedding.app.domain.PaymentReconciliationResolution;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.ProviderTransactionStatus;
import com.utewedding.app.domain.ProviderTransactionType;
import com.utewedding.app.domain.entity.CheckoutGroupEntity;
import com.utewedding.app.domain.entity.CheckoutPaymentAllocationEntity;
import com.utewedding.app.domain.entity.CheckoutRefundAllocationEntity;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderItemEntity;
import com.utewedding.app.domain.entity.PaymentAttemptEntity;
import com.utewedding.app.domain.entity.PaymentCallbackEntity;
import com.utewedding.app.domain.entity.PaymentEntity;
import com.utewedding.app.domain.entity.PaymentProviderTransactionEntity;
import com.utewedding.app.domain.entity.PaymentReconciliationEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.CheckoutGroupRepository;
import com.utewedding.app.repository.CheckoutPaymentAllocationRepository;
import com.utewedding.app.repository.CheckoutRefundAllocationRepository;
import com.utewedding.app.repository.OrderItemRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.PaymentAttemptRepository;
import com.utewedding.app.repository.PaymentCallbackRepository;
import com.utewedding.app.repository.PaymentProviderTransactionRepository;
import com.utewedding.app.repository.PaymentReconciliationRepository;
import com.utewedding.app.repository.PaymentRepository;
import com.utewedding.app.repository.ProductRepository;
import com.utewedding.app.service.FulfillmentService;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.impl.AccountingDocumentService;
import com.utewedding.app.service.impl.ShopFinancialService;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.port.DiscountPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Group-owned VNPAY state machine. Every capture is verified once and then allocated to child orders. */
@Service
public class GroupedPaymentIntegrityService {
    public enum SessionState { READY, PROCESSING, BLOCKED }

    public record VnpayGroupSession(UUID attemptId, String providerReference, long amount, String currency,
                                    String providerCreateDate, Instant expiresAt, String orderInfo, String clientIp) { }

    public record SessionResult(SessionState status, UUID groupId, String groupNumber, long totalAmount,
                                String currency, Instant expiresAt, VnpayGroupSession session, String message) { }

    public record VnpayGroupReference(UUID groupId, String groupNumber, UUID attemptId,
                                      String providerReference, PaymentAttemptStatus status,
                                      CheckoutGroupPaymentStatus paymentStatus) { }

    private enum PrepareState { READY, QUERY_REQUIRED, PROCESSING, BLOCKED }

    private record Prepared(PrepareState state, CheckoutGroupEntity group, PaymentAttemptEntity attempt,
                            PaymentGatewayClient gateway, PaymentGatewayClient.QueryCommand query,
                            VnpayGroupSession session, String message) { }

    private record RefundPlan(UUID transactionId, UUID groupId, UUID attemptId, String key,
                              PaymentGatewayClient gateway, PaymentGatewayClient.RefundCommand command,
                              UUID orderId, UUID captureAllocationId, boolean lateCapture) { }

    private record ChildRefundPlan(UUID transactionId, UUID groupId, UUID orderId,
                                   UUID captureAllocationId, String key,
                                   PaymentGatewayClient gateway, PaymentGatewayClient.RefundCommand command,
                                   UUID actorId, RequestContext context) { }

    private record ChildRefundPreparation(ChildRefundPlan plan,
                                          PaymentIntegrityService.RefundApplication application) {
        static ChildRefundPreparation plan(ChildRefundPlan plan) {
            return new ChildRefundPreparation(plan, null);
        }
        static ChildRefundPreparation replay() {
            return new ChildRefundPreparation(null, PaymentIntegrityService.RefundApplication.REPLAY);
        }
    }

    private record ChildRefundBooking(boolean succeeded,
                                      PaymentIntegrityService.RefundApplication application,
                                      String message) { }

    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final int PAYLOAD_LIMIT = 4000;

    private final CheckoutGroupRepository groups;
    private final CheckoutPaymentAllocationRepository allocations;
    private CheckoutRefundAllocationRepository refundAllocations;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final ProductRepository products;
    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PaymentProviderTransactionRepository transactions;
    private final PaymentCallbackRepository callbacks;
    private final PaymentReconciliationRepository reconciliations;
    private final DiscountPolicy discounts;
    private final FulfillmentService fulfillment;
    private final AuditLogPort audit;
    private final List<PaymentGatewayClient> gateways;
    private final TransactionTemplate transaction;
    private AccountingDocumentService accountingDocuments;
    private ShopFinancialService shopFinancial;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachRefundAllocations(CheckoutRefundAllocationRepository repository) {
        this.refundAllocations = repository;
    }

    /**
     * Refunds one VNPAY child against its immutable allocation of the group's verified capture.
     * The provider request runs only after the reservation transaction commits.
     */
    public PaymentIntegrityService.RefundApplication refundChild(UUID actorId, UUID orderId, String provider,
                                                                  com.utewedding.app.dto.request.PaymentRequest request,
                                                                  RequestContext context,
                                                                  boolean allowReturnCase) {
        if (refundAllocations == null) {
            throw new BusinessConflictException("Không thể tải phân bổ hoàn tiền của nhóm checkout.");
        }
        String key = blankToNull(request == null ? null : request.providerTransactionId());
        if (key == null || key.length() > 190) {
            throw new BusinessConflictException("Mã hoàn tiền hợp lệ là bắt buộc.");
        }
        UUID keyResolutionId = PaymentIntegrityService.returnResolutionId(key);
        if (!allowReturnCase && keyResolutionId != null) {
            throw new BusinessConflictException("Khóa return chỉ được dùng trong luồng resolution hồ sơ.");
        }
        UUID resolutionId = allowReturnCase ? keyResolutionId : null;
        if (allowReturnCase && resolutionId == null) {
            throw new BusinessConflictException("Khóa hoàn tiền không gắn với hồ sơ trả hàng.");
        }
        ChildRefundPreparation preparation = transaction.execute(status ->
                prepareChildRefund(actorId, orderId, provider, request, key, resolutionId, context));
        if (preparation == null) throw new BusinessConflictException("Không thể chuẩn bị yêu cầu hoàn tiền.");
        if (preparation.application() != null) return preparation.application();
        if (preparation.plan() == null) throw new BusinessConflictException("Không thể chuẩn bị yêu cầu hoàn tiền.");
        return executeChildRefund(preparation.plan());
    }

    private ChildRefundPreparation prepareChildRefund(UUID actorId, UUID orderId, String provider,
                                                       com.utewedding.app.dto.request.PaymentRequest request,
                                                       String key, UUID resolutionId, RequestContext context) {
        OrderEntity snapshot = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        UUID groupId = snapshot.getCheckoutGroupId();
        if (groupId == null) throw new BusinessConflictException("Đơn hàng không thuộc nhóm checkout.");
        CheckoutGroupEntity group = groups.findByIdForUpdate(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhóm checkout."));
        List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(groupId);
        OrderEntity child = children.stream().filter(row -> orderId.equals(row.getId())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        if (!groupId.equals(child.getCheckoutGroupId()) || !group.getCustomerId().equals(child.getUserId())) {
            throw new BusinessConflictException("Đơn hàng không thuộc nhóm checkout đã xác định.");
        }
        if (!"VNPAY".equalsIgnoreCase(group.getPaymentMethod())
                || provider == null || !provider.equalsIgnoreCase(group.getPaymentMethod())) {
            throw new BusinessConflictException("Hoàn tiền qua cổng chỉ áp dụng cho capture VNPAY của nhóm.");
        }

        CheckoutPaymentAllocationEntity allocation = allocations.findByGroupIdAndOrderId(groupId, orderId)
                .orElseThrow(() -> new BusinessConflictException("Đơn chưa có phân bổ capture nhóm để hoàn tiền."));
        Optional<UUID> lockedAllocationId = refundAllocations.lockCaptureAllocationByGroupIdAndOrderId(
                groupId, orderId);
        if (lockedAllocationId.isEmpty() || !allocation.getId().equals(lockedAllocationId.get())) {
            throw new BusinessConflictException("Không thể khóa phân bổ capture của đơn để hoàn tiền.");
        }
        PaymentProviderTransactionEntity capture = transactions.findById(allocation.getCaptureTransactionId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy capture nhóm gốc."));
        if (!groupId.equals(capture.getCheckoutGroupId()) || capture.getOrderId() != null
                || capture.getPaymentId() != null || capture.getTransactionType() != ProviderTransactionType.CAPTURE
                || capture.getStatus() != ProviderTransactionStatus.SUCCEEDED
                || !provider.equalsIgnoreCase(capture.getProvider())
                || capture.getAmount() != group.getTotalAmount()
                || blankToNull(capture.getProviderTransactionId()) == null
                || !Objects.equals(capture.getCurrency(), allocation.getCurrency())
                || !Objects.equals(child.getCurrency(), allocation.getCurrency())) {
            throw new BusinessConflictException("Phân bổ không thuộc capture nhóm đã xác minh.");
        }
        PaymentAttemptEntity attempt = capture.getAttemptId() == null ? null
                : attempts.findById(capture.getAttemptId()).orElse(null);
        PaymentGatewayClient gateway = gateway(provider);
        if (attempt == null || attempt.getOrderId() != null || !groupId.equals(attempt.getCheckoutGroupId())
                || !provider.equalsIgnoreCase(attempt.getProvider())
                || attempt.getAmount() != group.getTotalAmount()
                || !Objects.equals(attempt.getCurrency(), capture.getCurrency())
                || attempt.getProviderEnvironment() == null
                || capture.getProviderEnvironment() != attempt.getProviderEnvironment() || gateway == null
                || gateway.providerEnvironment() != attempt.getProviderEnvironment()) {
            throw new BusinessConflictException("Cổng thanh toán chưa sẵn sàng để hoàn tiền.");
        }

        PaymentProviderTransactionEntity existing = transactions.findByIdempotencyKeyForUpdate(key).orElse(null);
        if (existing != null) {
            CheckoutRefundAllocationEntity attribution = refundAllocations
                    .findByGroupIdAndOrderIdOrderByCreatedAtAsc(groupId, orderId).stream()
                    .filter(row -> existing.getId().equals(row.getRefundTransactionId()))
                    .findFirst().orElse(null);
            validateChildRefundIntent(existing, attribution, groupId, orderId, allocation.getId(), capture,
                    provider, request == null ? null : request.amount(), resolutionId);
            if (existing.getStatus() == ProviderTransactionStatus.SUCCEEDED) {
                return ChildRefundPreparation.replay();
            }
            if (existing.getStatus() == ProviderTransactionStatus.FAILED) {
                throw new BusinessConflictException("Yêu cầu hoàn tiền này đã thất bại. Dùng mã mới để thử lại.");
            }
            if (existing.getStatus() == ProviderTransactionStatus.PROCESSING) {
                throw new BusinessConflictException("Yêu cầu hoàn tiền này đang được xử lý.");
            }
            requireRefundableGroupChild(group, child);
            requireActiveRefundHoldsWithinCaps(capture, allocation);
            return claimChildRefund(actorId, child, group, allocation, capture, attempt,
                    existing, gateway, context);
        }

        requireRefundableGroupChild(group, child);
        PaymentEntity projection = payments.findByCheckoutPaymentAllocationId(allocation.getId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy payment projection của capture nhóm."));
        if (!orderId.equals(projection.getOrderId()) || projection.getCheckoutPaymentAllocationId() == null
                || !allocation.getId().equals(projection.getCheckoutPaymentAllocationId())
                || !provider.equalsIgnoreCase(projection.getProvider())
                || projection.getAmount() != allocation.getAmount()
                || !Objects.equals(projection.getCurrency(), allocation.getCurrency())
                || (projection.getStatus() != PaymentStatus.SUCCEEDED
                    && projection.getStatus() != PaymentStatus.REFUND_PENDING
                    && projection.getStatus() != PaymentStatus.PARTIALLY_REFUNDED
                    && projection.getStatus() != PaymentStatus.REFUND_FAILED)) {
            throw new BusinessConflictException("Payment projection không khớp phân bổ capture của đơn.");
        }

        List<CheckoutPaymentAllocationEntity> captureAllocations =
                allocations.findByCaptureTransactionIdOrderByOrderIdAsc(capture.getId());
        long allocatedCapture = captureAllocations.stream().mapToLong(CheckoutPaymentAllocationEntity::getAmount)
                .reduce(0L, Math::addExact);
        if (captureAllocations.isEmpty() || allocatedCapture != capture.getAmount()) {
            throw new BusinessConflictException("Tổng phân bổ child không khớp capture nhóm.");
        }
        requireActiveRefundHoldsWithinCaps(capture, allocation);
        long activeForChild = refundAllocations.sumActiveRefundAmountsByCaptureAllocationId(allocation.getId());
        long childRemaining = Math.subtractExact(allocation.getAmount(), activeForChild);
        long activeForCapture = activeRefundsForCapture(captureAllocations);
        long captureRemaining = Math.subtractExact(capture.getAmount(), activeForCapture);
        Long requestedAmount = request == null ? null : request.amount();
        long amount = requestedAmount == null ? Math.min(childRemaining, captureRemaining) : requestedAmount;
        if (amount <= 0 || amount > childRemaining || amount > captureRemaining) {
            throw new BusinessConflictException("Số tiền hoàn vượt số đã thu còn lại của đơn hoặc capture nhóm.");
        }

        Instant now = Instant.now();
        PaymentProviderTransactionEntity intent = groupTransaction(group, attempt, provider, null,
                ProviderTransactionType.REFUND, amount, ProviderTransactionStatus.PENDING, key,
                null, "Child refund intent recorded", null, now);
        intent.setProviderRequestId(capture.getProviderTransactionId());
        intent.setProcessedAt(null);
        if (resolutionId != null) intent.setReturnResolutionId(resolutionId);
        intent = transactions.saveAndFlush(intent);
        saveRefundAttribution(group, allocation, child, intent, amount);
        return claimChildRefund(actorId, child, group, allocation, capture, attempt, intent, gateway, context);
    }

    private void requireRefundableGroupChild(CheckoutGroupEntity group, OrderEntity child) {
        if (group.getPaymentStatus() != CheckoutGroupPaymentStatus.PAID
                && group.getPaymentStatus() != CheckoutGroupPaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessConflictException("Nhóm chưa có capture đã xác minh để hoàn tiền.");
        }
        if (child.getStatus() != OrderStatus.PAID) {
            throw new BusinessConflictException("Đơn hàng không ở trạng thái có thể hoàn tiền.");
        }
    }

    private void requireActiveRefundHoldsWithinCaps(PaymentProviderTransactionEntity capture,
                                                     CheckoutPaymentAllocationEntity childAllocation) {
        List<CheckoutPaymentAllocationEntity> captureAllocations =
                allocations.findByCaptureTransactionIdOrderByOrderIdAsc(capture.getId());
        long allocatedCapture = captureAllocations.stream().mapToLong(CheckoutPaymentAllocationEntity::getAmount)
                .reduce(0L, Math::addExact);
        long activeForCapture = activeRefundsForCapture(captureAllocations);
        long activeForChild = refundAllocations.sumActiveRefundAmountsByCaptureAllocationId(childAllocation.getId());
        if (captureAllocations.isEmpty() || allocatedCapture != capture.getAmount()
                || activeForCapture > capture.getAmount() || activeForChild > childAllocation.getAmount()) {
            throw new BusinessConflictException("Các khoản hoàn đang giữ vượt tổng capture hoặc phân bổ của đơn.");
        }
    }

    private long activeRefundsForCapture(List<CheckoutPaymentAllocationEntity> captureAllocations) {
        long activeForCapture = 0;
        for (CheckoutPaymentAllocationEntity allocation : captureAllocations) {
            activeForCapture = Math.addExact(activeForCapture,
                    refundAllocations.sumActiveRefundAmountsByCaptureAllocationId(allocation.getId()));
        }
        return activeForCapture;
    }

    private ChildRefundPreparation claimChildRefund(UUID actorId, OrderEntity child, CheckoutGroupEntity group,
                                                     CheckoutPaymentAllocationEntity allocation,
                                                     PaymentProviderTransactionEntity capture,
                                                     PaymentAttemptEntity attempt,
                                                     PaymentProviderTransactionEntity intent,
                                                     PaymentGatewayClient gateway, RequestContext context) {
        PaymentEntity projection = payments.findByCheckoutPaymentAllocationId(allocation.getId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy payment projection của capture nhóm."));
        intent.setStatus(ProviderTransactionStatus.PROCESSING);
        intent.setProcessedAt(Instant.now());
        transactions.save(intent);
        if (projection.getStatus() != PaymentStatus.REFUND_PENDING) {
            projection.transitionTo(PaymentStatus.REFUND_PENDING);
        }
        payments.save(projection);
        child.setPaymentStatus(PaymentStatus.REFUND_PENDING);
        orders.save(child);
        PaymentGatewayClient.RefundCommand command = new PaymentGatewayClient.RefundCommand(
                providerReference(attempt), capture.getProviderTransactionId(), attempt.getProviderCreateDate(),
                intent.getAmount(), capture.getAmount(), intent.getAmount() != capture.getAmount(),
                intent.getIdempotencyKey(), actorId == null ? "system" : actorId.toString(), ip(context),
                "Hoan tien don " + child.getOrderNumber() + " trong nhom " + group.getGroupNumber());
        return ChildRefundPreparation.plan(new ChildRefundPlan(intent.getId(), group.getId(), child.getId(),
                allocation.getId(), intent.getIdempotencyKey(), gateway, command, actorId, context));
    }

    private void validateChildRefundIntent(PaymentProviderTransactionEntity intent,
                                           CheckoutRefundAllocationEntity attribution,
                                           UUID groupId, UUID orderId, UUID captureAllocationId,
                                           PaymentProviderTransactionEntity capture,
                                           String provider, Long requestedAmount, UUID resolutionId) {
        if (intent.getTransactionType() != ProviderTransactionType.REFUND
                || !groupId.equals(intent.getCheckoutGroupId()) || intent.getOrderId() != null
                || intent.getPaymentId() != null || intent.getAttemptId() == null
                || !provider.equalsIgnoreCase(intent.getProvider())
                || !Objects.equals(capture.getProviderTransactionId(), intent.getProviderRequestId())
                || !Objects.equals(resolutionId, intent.getReturnResolutionId())
                || requestedAmount != null && requestedAmount != intent.getAmount()
                || attribution == null || !orderId.equals(attribution.getOrderId())
                || !groupId.equals(attribution.getGroupId())
                || !captureAllocationId.equals(attribution.getCaptureAllocationId())
                || attribution.getAmount() != intent.getAmount()
                || !Objects.equals(attribution.getCurrency(), intent.getCurrency())) {
            throw new BusinessConflictException("Khóa hoàn tiền đã được dùng cho nội dung khác.");
        }
    }

    private void saveRefundAttribution(CheckoutGroupEntity group,
                                       CheckoutPaymentAllocationEntity captureAllocation,
                                       OrderEntity child, PaymentProviderTransactionEntity intent, long amount) {
        CheckoutRefundAllocationEntity attribution = new CheckoutRefundAllocationEntity();
        attribution.setGroupId(group.getId());
        attribution.setOrderId(child.getId());
        attribution.setRefundTransactionId(intent.getId());
        attribution.setCaptureAllocationId(captureAllocation.getId());
        attribution.setAmount(amount);
        attribution.setCurrency(captureAllocation.getCurrency());
        refundAllocations.saveAndFlush(attribution);
    }

    private PaymentIntegrityService.RefundApplication executeChildRefund(ChildRefundPlan plan) {
        PaymentGatewayClient.RefundResult result;
        try {
            result = plan.gateway().refund(plan.command());
        } catch (RuntimeException exception) {
            transaction.executeWithoutResult(status -> {
                CheckoutGroupEntity group = groups.findByIdForUpdate(plan.groupId()).orElse(null);
                PaymentProviderTransactionEntity intent = transactions.findByIdForUpdate(plan.transactionId())
                        .orElse(null);
                if (group != null && intent != null
                        && intent.getStatus() == ProviderTransactionStatus.PROCESSING) {
                    intent.setStatus(ProviderTransactionStatus.PENDING);
                    intent.setProcessedAt(null);
                    intent.setResponseMessage("Provider result is unknown; retry with the same idempotency key.");
                    transactions.save(intent);
                }
            });
            throw new BusinessConflictException("Kết quả hoàn tiền đang chờ xác nhận từ cổng; hãy thử lại bằng cùng mã.");
        }
        ChildRefundBooking booking = transaction.execute(status -> finishChildRefund(plan, result));
        if (booking == null) throw new BusinessConflictException("Không thể ghi nhận kết quả hoàn tiền.");
        if (!booking.succeeded()) {
            throw new BusinessConflictException(booking.message() == null || booking.message().isBlank()
                    ? "Cổng thanh toán từ chối hoàn tiền."
                    : "Cổng thanh toán từ chối hoàn tiền: " + booking.message());
        }
        return booking.application();
    }

    private ChildRefundBooking finishChildRefund(ChildRefundPlan plan,
                                                  PaymentGatewayClient.RefundResult result) {
        CheckoutGroupEntity group = groups.findByIdForUpdate(plan.groupId()).orElse(null);
        if (group == null) throw new ResourceNotFoundException("Không tìm thấy nhóm checkout.");
        List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(plan.groupId());
        OrderEntity child = children.stream().filter(row -> plan.orderId().equals(row.getId())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        PaymentProviderTransactionEntity intent = transactions.findByIdForUpdate(plan.transactionId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy refund intent."));
        if (intent.getStatus() == ProviderTransactionStatus.SUCCEEDED) {
            return new ChildRefundBooking(true, PaymentIntegrityService.RefundApplication.REPLAY,
                    "Yêu cầu hoàn tiền đã được xử lý.");
        }
        if (intent.getStatus() != ProviderTransactionStatus.PROCESSING) {
            throw new BusinessConflictException("Refund intent đã được xử lý bởi một thao tác khác.");
        }
        CheckoutRefundAllocationEntity attribution = refundAllocations
                .findByGroupIdAndOrderIdOrderByCreatedAtAsc(group.getId(), child.getId()).stream()
                .filter(row -> intent.getId().equals(row.getRefundTransactionId()))
                .findFirst().orElseThrow(() -> new BusinessConflictException("Refund intent thiếu phân bổ child."));
        if (!plan.captureAllocationId().equals(attribution.getCaptureAllocationId())
                || attribution.getAmount() != intent.getAmount()) {
            throw new BusinessConflictException("Phân bổ refund không khớp đơn con.");
        }
        intent.setStatus(result.success() ? ProviderTransactionStatus.SUCCEEDED : ProviderTransactionStatus.FAILED);
        intent.setProviderTransactionId(blankToNull(result.providerTransactionId()));
        intent.setResponseCode(limit(result.responseCode(), 40));
        intent.setResponseMessage(limit(result.message(), 500));
        intent.setRawResponse(limit(result.rawBody(), PAYLOAD_LIMIT));
        intent.setProcessedAt(Instant.now());
        transactions.save(intent);
        PaymentEntity projection = payments.findByCheckoutPaymentAllocationId(plan.captureAllocationId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy payment projection của capture nhóm."));
        CheckoutPaymentAllocationEntity childCaptureAllocation = allocations.findById(plan.captureAllocationId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy capture allocation."));
        long succeededForChild = succeededRefundAmount(group.getId(), child.getId());
        boolean pendingForChild = hasPendingRefund(group.getId(), child.getId(), intent.getId());
        PaymentStatus previousPayment = projection.getStatus();
        PaymentStatus targetPayment = succeededForChild >= childCaptureAllocation.getAmount()
                ? PaymentStatus.REFUNDED
                : pendingForChild ? PaymentStatus.REFUND_PENDING
                : succeededForChild > 0 ? PaymentStatus.PARTIALLY_REFUNDED
                : PaymentStatus.REFUND_FAILED;
        projection.setRefundedAmount(succeededForChild);
        if (projection.getStatus() != targetPayment) projection.transitionTo(targetPayment);
        payments.save(projection);
        child.setPaymentStatus(targetPayment);
        OrderStatus previousOrder = child.getStatus();
        if (targetPayment == PaymentStatus.REFUNDED && child.getStatus() == OrderStatus.PAID) {
            int changed = orders.compareAndSetStatus(child.getId(), OrderStatus.PAID.name(), OrderStatus.REFUNDED.name(),
                    Instant.now());
            if (changed != 1) throw new BusinessConflictException("Đơn hàng đã được cập nhật bởi một thao tác khác.");
            child.transitionTo(OrderStatus.REFUNDED);
        } else if (child.getStatus() != OrderStatus.PAID && child.getStatus() != OrderStatus.REFUNDED) {
            throw new BusinessConflictException("Đơn hàng đã thay đổi trong lúc xử lý hoàn tiền.");
        }
        orders.save(child);
        long succeededForCapture = 0;
        List<CheckoutPaymentAllocationEntity> captureAllocations =
                allocations.findByCaptureTransactionIdOrderByOrderIdAsc(
                        childCaptureAllocation.getCaptureTransactionId());
        for (CheckoutPaymentAllocationEntity allocation : captureAllocations) {
            List<CheckoutRefundAllocationEntity> rows = refundAllocations
                    .findByCaptureAllocationIdOrderByCreatedAtAsc(allocation.getId());
            for (CheckoutRefundAllocationEntity row : rows) {
                PaymentProviderTransactionEntity refund = transactions.findById(row.getRefundTransactionId()).orElse(null);
                if (refund != null && refund.getStatus() == ProviderTransactionStatus.SUCCEEDED) {
                    succeededForCapture = Math.addExact(succeededForCapture, row.getAmount());
                }
            }
        }
        if (succeededForCapture >= captureAllocations.stream()
                .mapToLong(CheckoutPaymentAllocationEntity::getAmount).reduce(0L, Math::addExact)) {
            group.setPaymentStatus(CheckoutGroupPaymentStatus.REFUNDED);
        } else if (succeededForCapture > 0) {
            group.setPaymentStatus(CheckoutGroupPaymentStatus.PARTIALLY_REFUNDED);
        }
        groups.save(group);
        if (result.success() && accountingDocuments != null) {
            accountingDocuments.ensure(child, com.utewedding.app.domain.AccountingDocumentType.CREDIT_NOTE,
                    result.providerTransactionId() == null ? plan.key() : result.providerTransactionId(),
                    intent.getAmount());
        }
        if (audit != null) {
            if (previousOrder != child.getStatus()) {
                audit.record("ORDER_STATUS_CHANGED", plan.actorId(), "Order", child.getId(),
                        Map.of("from", previousOrder.name(), "to", child.getStatus().name(),
                                "checkoutGroupId", group.getId().toString()), plan.context());
            }
            audit.record("PAYMENT_STATUS_CHANGED", plan.actorId(), "Payment", projection.getId(),
                    Map.of("status", previousPayment.name() + " -> " + targetPayment.name(),
                            "checkoutGroupId", group.getId().toString(), "amount", intent.getAmount()),
                    plan.context());
        }
        return new ChildRefundBooking(result.success(), PaymentIntegrityService.RefundApplication.APPLIED,
                result.message());
    }

    private long succeededRefundAmount(UUID groupId, UUID orderId) {
        long total = 0;
        for (CheckoutRefundAllocationEntity row : refundAllocations.findByGroupIdAndOrderIdOrderByCreatedAtAsc(
                groupId, orderId)) {
            PaymentProviderTransactionEntity intent = transactions.findById(row.getRefundTransactionId()).orElse(null);
            if (intent != null && intent.getStatus() == ProviderTransactionStatus.SUCCEEDED) {
                total = Math.addExact(total, row.getAmount());
            }
        }
        return total;
    }

    private boolean hasPendingRefund(UUID groupId, UUID orderId, UUID exceptTransactionId) {
        return refundAllocations.findByGroupIdAndOrderIdOrderByCreatedAtAsc(groupId, orderId).stream()
                .filter(row -> !exceptTransactionId.equals(row.getRefundTransactionId()))
                .map(row -> transactions.findById(row.getRefundTransactionId()).orElse(null))
                .anyMatch(intent -> intent != null && (intent.getStatus() == ProviderTransactionStatus.PENDING
                        || intent.getStatus() == ProviderTransactionStatus.PROCESSING));
    }

    public GroupedPaymentIntegrityService(CheckoutGroupRepository groups,
                                          CheckoutPaymentAllocationRepository allocations,
                                          OrderRepository orders, OrderItemRepository orderItems,
                                          ProductRepository products, PaymentRepository payments,
                                          PaymentAttemptRepository attempts,
                                          PaymentProviderTransactionRepository transactions,
                                          PaymentCallbackRepository callbacks,
                                          PaymentReconciliationRepository reconciliations,
                                          @org.springframework.beans.factory.annotation.Qualifier("voucherDiscountPolicy")
                                          DiscountPolicy discounts, FulfillmentService fulfillment,
                                          AuditLogPort audit, List<PaymentGatewayClient> gateways,
                                          PlatformTransactionManager transactionManager) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.allocations = Objects.requireNonNull(allocations, "allocations");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.orderItems = Objects.requireNonNull(orderItems, "orderItems");
        this.products = Objects.requireNonNull(products, "products");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.reconciliations = Objects.requireNonNull(reconciliations, "reconciliations");
        this.discounts = discounts;
        this.fulfillment = fulfillment;
        this.audit = audit;
        this.gateways = gateways == null ? List.of() : List.copyOf(gateways);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachAccountingDocuments(AccountingDocumentService value) { accountingDocuments = value; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachShopFinancialService(ShopFinancialService value) { shopFinancial = value; }

    public SessionResult createOrReuseVnpaySession(UUID actorId, UUID groupId, String orderInfo,
                                                    String clientIp, int expireMinutes,
                                                    RequestContext context) {
        Prepared prepared = transaction.execute(status -> prepareSession(actorId, groupId, orderInfo,
                clientIp, expireMinutes, context));
        if (prepared == null) return blocked(null, "Không tìm thấy nhóm checkout.");
        if (prepared.state() == PrepareState.READY) return ready(prepared.group(), prepared.session());
        if (prepared.state() == PrepareState.BLOCKED) return blocked(prepared.group(), prepared.message());
        if (prepared.state() == PrepareState.PROCESSING) return processing(prepared.group(), prepared.message());

        PaymentGatewayClient.QueryResult result;
        try {
            result = prepared.gateway().query(prepared.query());
        } catch (RuntimeException exception) {
            result = new PaymentGatewayClient.QueryResult(PaymentGatewayClient.QUERY_FAILED,
                    null, null, "HTTP", "Không thể xác minh phiên thanh toán cũ.", null);
        }
        PaymentGatewayClient.QueryResult queryResult = result;
        Prepared afterQuery = transaction.execute(status -> {
            CheckoutGroupEntity group = groups.findByIdForUpdate(groupId).orElse(null);
            if (group == null) return blockedPrepared(null, "Không tìm thấy nhóm checkout.");
            List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(groupId);
            PaymentAttemptEntity attempt = attempts.findById(prepared.attempt().getId()).orElse(null);
            if (attempt == null || !groupId.equals(attempt.getCheckoutGroupId())) {
                return blockedPrepared(group, "Không tìm thấy phiên VNPAY đã lưu.");
            }
            applyQuery(group, children, attempt, queryResult, context);
            if (group.getPaymentStatus() == CheckoutGroupPaymentStatus.PAID
                    || group.getPaymentStatus() == CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED
                    || group.getLifecycleStatus() != CheckoutGroupLifecycleStatus.PENDING) {
                return blockedPrepared(group, "Nhóm đã được thanh toán, đóng hoặc cần đối soát.");
            }
            if (PaymentGatewayClient.FAILED.equals(queryResult.providerState())
                    || PaymentGatewayClient.NOT_FOUND.equals(queryResult.providerState())) {
                return prepareSessionLocked(actorId, group, orderInfo, clientIp, expireMinutes, context);
            }
            return processingPrepared(group, "Đang xác minh phiên VNPAY cũ. Vui lòng thử lại sau.");
        });
        if (afterQuery == null) return blocked(null, "Không tìm thấy nhóm checkout.");
        if (afterQuery.state() == PrepareState.READY) return ready(afterQuery.group(), afterQuery.session());
        if (afterQuery.state() == PrepareState.PROCESSING) return processing(afterQuery.group(), afterQuery.message());
        return blocked(afterQuery.group(), afterQuery.message());
    }

    private Prepared prepareSession(UUID actorId, UUID groupId, String orderInfo, String clientIp,
                                    int expireMinutes, RequestContext context) {
        CheckoutGroupEntity group = groups.findByIdForUpdate(groupId).orElse(null);
        if (group == null || actorId == null || !actorId.equals(group.getCustomerId())) {
            throw new ResourceNotFoundException("Không tìm thấy nhóm checkout.");
        }
        List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(groupId);
        return prepareSessionLocked(actorId, group, orderInfo, clientIp, expireMinutes, context, children);
    }

    private Prepared prepareSessionLocked(UUID actorId, CheckoutGroupEntity group, String orderInfo,
                                          String clientIp, int expireMinutes, RequestContext context) {
        return prepareSessionLocked(actorId, group, orderInfo, clientIp, expireMinutes, context,
                orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(group.getId()));
    }

    private Prepared prepareSessionLocked(UUID actorId, CheckoutGroupEntity group, String orderInfo,
                                          String clientIp, int expireMinutes, RequestContext context,
                                          List<OrderEntity> children) {
        if (group == null) return blockedPrepared(null, "Không tìm thấy nhóm checkout.");
        if (actorId != null && !actorId.equals(group.getCustomerId())) {
            throw new ResourceNotFoundException("Không tìm thấy nhóm checkout.");
        }
        if (!"VNPAY".equalsIgnoreCase(group.getPaymentMethod())) {
            return blockedPrepared(group, "Nhóm checkout không sử dụng VNPAY.");
        }
        if (group.getTotalAmount() <= 0) {
            return blockedPrepared(group, "Nhóm có số tiền phải trả bằng 0 nên không cần tạo phiên VNPAY.");
        }
        if (group.getPaymentStatus() == CheckoutGroupPaymentStatus.PAID
                || group.getPaymentStatus() == CheckoutGroupPaymentStatus.PARTIALLY_REFUNDED
                || group.getPaymentStatus() == CheckoutGroupPaymentStatus.REFUNDED) {
            return blockedPrepared(group, "Thanh toán của nhóm đã được ghi nhận.");
        }
        if (group.getLifecycleStatus() != CheckoutGroupLifecycleStatus.PENDING
                || group.getPaymentStatus() == CheckoutGroupPaymentStatus.CANCELLED
                || group.getPaymentStatus() == CheckoutGroupPaymentStatus.EXPIRED) {
            return blockedPrepared(group, "Nhóm checkout đã đóng và không thể thanh toán.");
        }
        Instant now = Instant.now();
        if (group.getExpiresAt() == null || !group.getExpiresAt().isAfter(now)) {
            if (group.getExpiresAt() != null) expireGroupLocked(group, children, now, context);
            return blockedPrepared(group, "Nhóm checkout đã hết hạn thanh toán.");
        }
        if (group.getPaymentStatus() == CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED) {
            return processingPrepared(group, "Nhóm thanh toán cần đối soát trước khi tạo phiên mới.");
        }
        PaymentGatewayClient gateway = gateway("VNPAY");
        if (gateway == null || gateway.providerEnvironment() == null) {
            return blockedPrepared(group, "Môi trường VNPAY chưa được xác định an toàn.");
        }
        PaymentAttemptEntity latest = attempts.findByCheckoutGroupIdForUpdateOrderByCreatedAtDesc(group.getId())
                .stream().filter(row -> "VNPAY".equalsIgnoreCase(row.getProvider())).findFirst().orElse(null);
        if (latest != null && latest.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
            return blockedPrepared(group, "Thanh toán của nhóm đã được ghi nhận.");
        }
        if (latest != null && latest.getStatus() != null && latest.getStatus().isOpen()) {
            if (latest.getExpiresAt() != null && latest.getExpiresAt().isAfter(now)) {
                String reference = providerReference(latest);
                return readyPrepared(group, latest, new VnpayGroupSession(latest.getId(), reference,
                        latest.getAmount(), latest.getCurrency(), latest.getProviderCreateDate(),
                        latest.getExpiresAt(), safeText(latest.getProviderOrderInfo(),
                        defaultOrderInfo(group)), safeIp(latest.getProviderClientIp())));
            }
            return queryRequired(group, latest, gateway, context);
        }
        return createSessionLocked(group, children, gateway, orderInfo, clientIp, expireMinutes, context, now);
    }

    private Prepared createSessionLocked(CheckoutGroupEntity group, List<OrderEntity> children,
                                         PaymentGatewayClient gateway, String orderInfo, String clientIp,
                                         int expireMinutes, RequestContext context, Instant now) {
        if (group.getPaymentStatus() == CheckoutGroupPaymentStatus.PAID
                || group.getPaymentStatus() == CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED) {
            return blockedPrepared(group, "Nhóm thanh toán cần đối soát trước khi tạo phiên mới.");
        }
        long sessionSeconds = Math.max(60L, Math.multiplyExact((long) Math.max(1, expireMinutes), 60L));
        Instant sessionExpiry = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(sessionSeconds);
        Instant groupBound = group.getExpiresAt().minusSeconds(30);
        if (groupBound.isBefore(sessionExpiry)) sessionExpiry = groupBound;
        sessionExpiry = sessionExpiry.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        if (!sessionExpiry.isAfter(now.plusSeconds(30))) {
            return blockedPrepared(group, "Nhóm checkout còn quá ít thời gian để tạo phiên thanh toán mới.");
        }
        long childTotal = sumChildTotals(children);
        if (childTotal != group.getTotalAmount() || children.isEmpty()) {
            group.setPaymentStatus(CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED);
            return blockedPrepared(group, "Tổng nhóm không khớp tổng các đơn con; cần đối soát trước khi thanh toán.");
        }
        Instant createAt = now;
        String reference = newVnpayReference(group.getGroupNumber());
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setCheckoutGroupId(group.getId());
        attempt.setProvider("VNPAY");
        attempt.setProviderEnvironment(gateway.providerEnvironment());
        attempt.setMerchantReference(group.getGroupNumber());
        attempt.setProviderTxnRef(reference);
        attempt.setProviderCreateDate(VN_DATE.format(createAt));
        attempt.setProviderOrderInfo(limit(orderInfo == null || orderInfo.isBlank()
                ? defaultOrderInfo(group) : orderInfo, 255));
        attempt.setProviderClientIp(safeIp(clientIp));
        attempt.setAmount(group.getTotalAmount());
        attempt.setCurrency(group.getCurrency() == null ? "VND" : group.getCurrency());
        attempt.setStatus(PaymentAttemptStatus.PROCESSING);
        attempt.setExpiresAt(sessionExpiry);
        attempt.setCreatedAt(now);
        attempt.setUpdatedAt(now);
        attempt = attempts.saveAndFlush(attempt);

        for (OrderEntity child : children) {
            if (child.getStatus() != OrderStatus.PENDING || child.getPaymentStatus() == null
                    || child.getPaymentStatus().isCaptured()) {
                group.setPaymentStatus(CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED);
                return blockedPrepared(group, "Một đơn con không còn ở trạng thái chờ thanh toán.");
            }
            for (PaymentEntity payment : payments.findByOrderIdForUpdateOrderByIdAsc(child.getId())) {
                if ("VNPAY".equalsIgnoreCase(payment.getProvider())
                        && payment.getCheckoutPaymentAllocationId() == null
                        && (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.PROCESSING)) {
                    if (payment.getStatus() == PaymentStatus.PENDING) payment.transitionTo(PaymentStatus.PROCESSING);
                    payments.save(payment);
                }
            }
            if (child.getPaymentStatus() == PaymentStatus.PENDING) child.setPaymentStatus(PaymentStatus.PROCESSING);
            orders.save(child);
        }
        group.setPaymentStatus(CheckoutGroupPaymentStatus.PROCESSING);
        groups.save(group);
        return readyPrepared(group, attempt, new VnpayGroupSession(attempt.getId(), reference,
                attempt.getAmount(), attempt.getCurrency(), attempt.getProviderCreateDate(), sessionExpiry,
                attempt.getProviderOrderInfo(), attempt.getProviderClientIp()));
    }

    private Prepared queryRequired(CheckoutGroupEntity group, PaymentAttemptEntity attempt,
                                   PaymentGatewayClient gateway, RequestContext context) {
        return new Prepared(PrepareState.QUERY_REQUIRED, group, attempt, gateway,
                new PaymentGatewayClient.QueryCommand(providerReference(attempt),
                        attempt.getProviderCreateDate(), ip(context)), null, null);
    }

    public boolean ownsVnpayReference(String reference) {
        PaymentAttemptEntity attempt = reference == null ? null
                : attempts.findByProviderAndProviderTxnRef("VNPAY", reference).orElse(null);
        return attempt != null && attempt.getCheckoutGroupId() != null
                && "VNPAY".equalsIgnoreCase(attempt.getProvider())
                && reference.equals(providerReference(attempt));
    }

    public VnpayGroupReference resolveVnpayReference(String reference) {
        if (!ownsVnpayReference(reference)) return null;
        PaymentAttemptEntity attempt = attempts.findByProviderAndProviderTxnRef("VNPAY", reference).orElse(null);
        if (attempt == null || attempt.getCheckoutGroupId() == null) return null;
        CheckoutGroupEntity group = groups.findById(attempt.getCheckoutGroupId()).orElse(null);
        if (group == null) return null;
        return new VnpayGroupReference(group.getId(), group.getGroupNumber(), attempt.getId(),
                providerReference(attempt), attempt.getStatus(), group.getPaymentStatus());
    }

    public PaymentCallbackResult applyVerified(VerifiedPaymentCallback callback, RequestContext context) {
        String hash = payloadHash(callback.provider(), callback.canonicalPayload());
        return transaction.execute(status -> applyVerifiedLocked(callback, context, hash));
    }

    private PaymentCallbackResult applyVerifiedLocked(VerifiedPaymentCallback callback, RequestContext context,
                                                       String hash) {
        PaymentCallbackEntity old = callbacks.findByProviderAndPayloadSha256(callback.provider(), hash).orElse(null);
        if (old != null) return replay(old);
        String reference = callback.orderNumber();
        PaymentAttemptEntity attempt = reference == null ? null
                : attempts.findByProviderAndProviderTxnRef("VNPAY", reference).orElse(null);
        if (attempt == null || attempt.getCheckoutGroupId() == null
                || !reference.equals(providerReference(attempt))) {
            return recordGroupCallback(null, null, callback, hash, PaymentCallbackOutcome.ORDER_NOT_FOUND,
                    "01", "Order not found", true);
        }
        UUID groupId = attempt.getCheckoutGroupId();
        CheckoutGroupEntity group = groups.findByIdForUpdate(groupId).orElse(null);
        if (group == null) {
            return recordGroupCallback(groupId, attempt, callback, hash, PaymentCallbackOutcome.ORDER_NOT_FOUND,
                    "01", "Checkout group not found", true);
        }
        List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(groupId);
        attempt = attempts.findById(attempt.getId()).orElse(null);
        if (attempt == null || !groupId.equals(attempt.getCheckoutGroupId())
                || !reference.equals(providerReference(attempt))
                || !"VNPAY".equalsIgnoreCase(attempt.getProvider())) {
            return recordGroupCallback(groupId, null, callback, hash, PaymentCallbackOutcome.CONFLICT,
                    "04", "Invalid payment reference", true);
        }
        if (callback.amount() == null || callback.amount() < 0 || callback.amount() != group.getTotalAmount()
                || callback.amount() != attempt.getAmount() || !"VND".equalsIgnoreCase(group.getCurrency())
                || !"VND".equalsIgnoreCase(attempt.getCurrency())) {
            group.setPaymentStatus(CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED);
            groups.save(group);
            saveGroupReconciliation(group, attempt, callback.transactionStatus(), callback.providerTransactionId(),
                    callback.amount(), PaymentReconciliationReason.AMOUNT_MISMATCH,
                    PaymentReconciliationResolution.OPEN, "Số tiền VNPAY khác số tiền tổng nhóm hoặc attempt.");
            return recordGroupCallback(groupId, attempt, callback, hash, PaymentCallbackOutcome.AMOUNT_MISMATCH,
                    "04", "Invalid amount", true);
        }
        PaymentProviderEnvironment currentEnvironment = providerEnvironment("VNPAY");
        if (attempt.getProviderEnvironment() == null || currentEnvironment == null
                || attempt.getProviderEnvironment() != currentEnvironment) {
            saveGroupReconciliation(group, attempt, callback.transactionStatus(), callback.providerTransactionId(),
                    callback.amount(), PaymentReconciliationReason.QUERY_FAILED,
                    PaymentReconciliationResolution.OPEN, "Môi trường attempt VNPAY khác môi trường đang cấu hình.");
            group.setPaymentStatus(CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED);
            groups.save(group);
            return recordGroupCallback(groupId, attempt, callback, hash, PaymentCallbackOutcome.CONFLICT,
                    "04", "Payment environment mismatch", true);
        }
        if (!callback.success()) {
            if (attempt.getStatus() != null && attempt.getStatus().isOpen()) {
                attempt.setStatus(PaymentAttemptStatus.FAILED);
                attempt.setFailureCode(limit(callback.responseCode(), 40));
                attempt.setFailureReason(limit(callback.message(), 500));
                attempt.setUpdatedAt(Instant.now());
                attempts.save(attempt);
                if (group.getPaymentStatus() == CheckoutGroupPaymentStatus.PROCESSING) {
                    group.setPaymentStatus(CheckoutGroupPaymentStatus.PENDING);
                    groups.save(group);
                }
            }
            return recordGroupCallback(groupId, attempt, callback, hash, PaymentCallbackOutcome.PAYMENT_FAILED,
                    "00", "Transaction was not successful", true);
        }
        if (callback.providerTransactionId() == null || callback.providerTransactionId().isBlank()) {
            return recordGroupCallback(groupId, attempt, callback, hash, PaymentCallbackOutcome.CONFLICT,
                    "04", "Invalid transaction", true);
        }
        PaymentProviderTransactionEntity previous = transactions
                .findByProviderAndProviderTransactionIdAndTransactionType(
                        "VNPAY", callback.providerTransactionId(), ProviderTransactionType.CAPTURE).orElse(null);
        if (previous != null) {
            if (groupId.equals(previous.getCheckoutGroupId()) && previous.getStatus() == ProviderTransactionStatus.SUCCEEDED
                    && previous.getAmount() == callback.amount()) {
                return recordGroupCallback(groupId, attempt, callback, hash, PaymentCallbackOutcome.DUPLICATE,
                        "00", "Order already confirmed", true);
            }
            return recordGroupCallback(groupId, attempt, callback, hash, PaymentCallbackOutcome.CONFLICT,
                    "04", "Transaction already belongs to another payment", true);
        }
        boolean active = group.getLifecycleStatus() == CheckoutGroupLifecycleStatus.PENDING
                && (group.getPaymentStatus() == CheckoutGroupPaymentStatus.PENDING
                    || group.getPaymentStatus() == CheckoutGroupPaymentStatus.PROCESSING)
                && group.getExpiresAt() != null && group.getExpiresAt().isAfter(receivedAt(callback))
                && children.stream().allMatch(order -> order.getCheckoutGroupId().equals(groupId)
                    && order.getStatus() == OrderStatus.PENDING
                    && (order.getPaymentStatus() == PaymentStatus.PENDING
                        || order.getPaymentStatus() == PaymentStatus.PROCESSING));
        if (active) {
            if (sumChildTotals(children) != group.getTotalAmount()) {
                group.setPaymentStatus(CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED);
                groups.save(group);
                saveGroupReconciliation(group, attempt, PaymentGatewayClient.CAPTURED,
                        callback.providerTransactionId(), callback.amount(), PaymentReconciliationReason.AMOUNT_MISMATCH,
                        PaymentReconciliationResolution.OPEN, "Tổng đơn con không khớp capture nhóm.");
                return recordGroupCallback(groupId, attempt, callback, hash, PaymentCallbackOutcome.AMOUNT_MISMATCH,
                        "04", "Invalid amount", true);
            }
            captureGroup(group, children, attempt, callback, receivedAt(callback), context);
            return recordGroupCallback(groupId, attempt, callback, hash, PaymentCallbackOutcome.APPLIED,
                    "00", "Payment Success", true);
        }
        if (group.getLifecycleStatus() == CheckoutGroupLifecycleStatus.PENDING
                && group.getExpiresAt() != null && !group.getExpiresAt().isAfter(receivedAt(callback))) {
            expireGroupLocked(group, children, receivedAt(callback), context);
        }
        recordUnallocatedCapture(group, attempt, callback, context,
                "Nhóm đã đóng hoặc capture nhóm đã được ghi nhận.");
        return recordGroupCallback(groupId, attempt, callback, hash, PaymentCallbackOutcome.LATE_CAPTURE,
                "00", "Late capture acknowledged", true);
    }

    private void captureGroup(CheckoutGroupEntity group, List<OrderEntity> children, PaymentAttemptEntity attempt,
                              VerifiedPaymentCallback callback, Instant now, RequestContext context) {
        PaymentProviderTransactionEntity capture = groupTransaction(group, attempt, callback.provider(),
                callback.providerTransactionId(), ProviderTransactionType.CAPTURE, callback.amount(),
                ProviderTransactionStatus.SUCCEEDED,
                transactionKey("capture:VNPAY:" + callback.providerTransactionId()), callback.responseCode(),
                callback.message(), null, now);
        capture = transactions.saveAndFlush(capture);

        long allocated = 0;
        for (OrderEntity child : children.stream().sorted(Comparator.comparing(OrderEntity::getId)).toList()) {
            if (!group.getId().equals(child.getCheckoutGroupId()) || child.getTotalAmount() < 0
                    || !"VND".equalsIgnoreCase(child.getCurrency())) {
                throw new BusinessConflictException("Đơn con không thuộc nhóm hoặc có tiền tệ không hợp lệ.");
            }
            allocated = Math.addExact(allocated, child.getTotalAmount());
            CheckoutPaymentAllocationEntity allocation = new CheckoutPaymentAllocationEntity();
            allocation.setCaptureTransactionId(capture.getId());
            allocation.setGroupId(group.getId());
            allocation.setOrderId(child.getId());
            allocation.setAmount(child.getTotalAmount());
            allocation.setCurrency(child.getCurrency());
            allocation = allocations.saveAndFlush(allocation);

            PaymentEntity projection = captureProjection(child);
            projection.setCheckoutPaymentAllocationId(allocation.getId());
            projection.setAmount(child.getTotalAmount());
            projection.setCurrency(child.getCurrency());
            projection.setProvider("VNPAY");
            projection.setProviderTransactionId(null);
            if (projection.getStatus() != PaymentStatus.SUCCEEDED) projection.transitionTo(PaymentStatus.SUCCEEDED);
            projection.setFailureReason(null);
            projection.setProcessedAt(now);
            payments.saveAndFlush(projection);

            OrderStatus prior = child.getStatus();
            if (prior != OrderStatus.PAID) {
                int changed = orders.compareAndSetStatus(child.getId(), prior.name(), OrderStatus.PAID.name(), now);
                if (changed != 1) throw new BusinessConflictException("Đơn con đã thay đổi trong lúc ghi nhận capture nhóm.");
                child.transitionTo(OrderStatus.PAID);
            }
            child.setPaymentStatus(PaymentStatus.SUCCEEDED);
            child.setPaidAt(now);
            orders.save(child);
            if (accountingDocuments != null) {
                accountingDocuments.ensure(child, com.utewedding.app.domain.AccountingDocumentType.PAYMENT_RECEIPT,
                        callback.providerTransactionId());
            }
            if (shopFinancial != null) shopFinancial.recordSale(child);
            if (fulfillment != null) fulfillment.ensureForOrder(child.getId(), context);
            audit.record("ORDER_STATUS_CHANGED", null, "Order", child.getId(),
                    Map.of("from", prior.name(), "to", OrderStatus.PAID.name(), "provider", "VNPAY",
                            "checkoutGroupId", group.getId().toString()), context);
            audit.record("PAYMENT_STATUS_CHANGED", null, "Payment", projection.getId(),
                    Map.of("status", PaymentStatus.SUCCEEDED.name(), "provider", "VNPAY",
                            "checkoutPaymentAllocationId", allocation.getId().toString()), context);
        }
        if (children.isEmpty() || allocated != group.getTotalAmount() || allocated != callback.amount()) {
            throw new BusinessConflictException("Phân bổ capture nhóm không khớp tổng đơn con.");
        }
        attempt.setStatus(PaymentAttemptStatus.SUCCEEDED);
        attempt.setProviderTransactionId(callback.providerTransactionId());
        attempt.setFailureCode(null);
        attempt.setFailureReason(null);
        attempt.setUpdatedAt(now);
        attempts.save(attempt);
        group.setPaymentStatus(CheckoutGroupPaymentStatus.PAID);
        group.setLifecycleStatus(CheckoutGroupLifecycleStatus.CONFIRMED);
        groups.save(group);
        audit.record("CHECKOUT_GROUP_PAYMENT_CAPTURED", null, "CheckoutGroup", group.getId(),
                Map.of("amount", callback.amount(), "provider", "VNPAY",
                        "providerTransactionId", callback.providerTransactionId(), "orderCount", children.size()), context);
    }

    private PaymentEntity captureProjection(OrderEntity order) {
        List<PaymentEntity> rows = payments.findByOrderIdForUpdateOrderByIdAsc(order.getId());
        for (PaymentEntity payment : rows) {
            if ("VNPAY".equalsIgnoreCase(payment.getProvider())
                    && payment.getCheckoutPaymentAllocationId() == null
                    && payment.getProviderTransactionId() == null
                    && (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.PROCESSING)) {
                return payment;
            }
        }
        PaymentEntity payment = new PaymentEntity();
        payment.setOrderId(order.getId());
        payment.setProvider("VNPAY");
        payment.setAmount(order.getTotalAmount());
        payment.setCurrency(order.getCurrency());
        payment.setStatus(PaymentStatus.PENDING);
        return payment;
    }

    private void recordUnallocatedCapture(CheckoutGroupEntity group, PaymentAttemptEntity attempt,
                                          VerifiedPaymentCallback callback, RequestContext context,
                                          String reason) {
        Instant now = receivedAt(callback);
        if (callback.amount() == null || callback.amount() <= 0 || blankToNull(callback.providerTransactionId()) == null) {
            group.setPaymentStatus(CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED);
            groups.save(group);
            saveGroupReconciliation(group, attempt, PaymentGatewayClient.CAPTURED,
                    callback.providerTransactionId(), callback.amount(), PaymentReconciliationReason.QUERY_FAILED,
                    PaymentReconciliationResolution.OPEN, "Capture VNPAY muộn thiếu số tiền hoặc mã giao dịch.");
            return;
        }
        PaymentProviderTransactionEntity capture = groupTransaction(group, attempt, "VNPAY",
                callback.providerTransactionId(), ProviderTransactionType.CAPTURE, callback.amount(),
                ProviderTransactionStatus.SUCCEEDED,
                transactionKey("capture:VNPAY:" + callback.providerTransactionId()), callback.responseCode(),
                reason, null, now);
        transactions.saveAndFlush(capture);
        group.setPaymentStatus(CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED);
        groups.save(group);
        String refundKey = lateRefundKey(callback.providerTransactionId());
        PaymentProviderTransactionEntity refundIntent = groupTransaction(group, attempt, "VNPAY", null,
                ProviderTransactionType.REFUND, capture.getAmount(), ProviderTransactionStatus.PENDING,
                refundKey, null, "Late or duplicate capture; capture refund intent queued.", null, now);
        refundIntent.setProviderRequestId(capture.getProviderTransactionId());
        refundIntent.setProcessedAt(null);
        transactions.saveAndFlush(refundIntent);
        saveGroupReconciliation(group, attempt, PaymentGatewayClient.CAPTURED,
                callback.providerTransactionId(), callback.amount(), PaymentReconciliationReason.LOCAL_CLOSED_PROVIDER_CAPTURED,
                PaymentReconciliationResolution.OPEN,
                reason + " Đã tạo yêu cầu hoàn gắn với capture muộn để đối soát.");
        audit.record("CHECKOUT_GROUP_LATE_CAPTURE", null, "CheckoutGroup", group.getId(),
                Map.of("amount", callback.amount(), "provider", "VNPAY",
                        "providerTransactionId", callback.providerTransactionId(),
                        "refundQueued", true), context);
    }

    public PaymentReconciliationEntity reconcileVnpayGroup(UUID groupId, RequestContext context) {
        Prepared prepared = transaction.execute(status -> {
            CheckoutGroupEntity group = groups.findByIdForUpdate(groupId).orElse(null);
            if (group == null) throw new ResourceNotFoundException("Không tìm thấy nhóm checkout.");
            List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(groupId);
            PaymentAttemptEntity attempt = attempts.findByCheckoutGroupIdForUpdateOrderByCreatedAtDesc(groupId)
                    .stream().filter(row -> "VNPAY".equalsIgnoreCase(row.getProvider()))
                    .filter(row -> row.getStatus() != null && row.getStatus().isOpen())
                    .findFirst().orElse(null);
            if (attempt == null) {
                return blockedPrepared(group, "Không có attempt VNPAY đang mở để đối soát.");
            }
            PaymentGatewayClient gateway = gateway(attempt.getProvider());
            if (gateway == null || attempt.getProviderEnvironment() == null
                    || gateway.providerEnvironment() != attempt.getProviderEnvironment()) {
                return blockedPrepared(group, "Môi trường VNPAY của attempt không khớp cấu hình hiện tại.");
            }
            return queryRequired(group, attempt, gateway, context);
        });
        if (prepared == null || prepared.state() != PrepareState.QUERY_REQUIRED) return null;
        PaymentGatewayClient.QueryResult result;
        try {
            result = prepared.gateway().query(prepared.query());
        } catch (RuntimeException exception) {
            result = new PaymentGatewayClient.QueryResult(PaymentGatewayClient.QUERY_FAILED,
                    null, null, "HTTP", "Không thể đối soát VNPAY lúc này.", null);
        }
        PaymentGatewayClient.QueryResult verified = result;
        return transaction.execute(status -> {
            CheckoutGroupEntity group = groups.findByIdForUpdate(groupId).orElse(null);
            if (group == null) return null;
            List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(groupId);
            PaymentAttemptEntity attempt = attempts.findById(prepared.attempt().getId()).orElse(null);
            if (attempt == null || !groupId.equals(attempt.getCheckoutGroupId())) return null;
            return applyQuery(group, children, attempt, verified, context);
        });
    }

    public PaymentReconciliationEntity reconcileVnpayReference(String reference, RequestContext context) {
        VnpayGroupReference resolved = resolveVnpayReference(reference);
        return resolved == null ? null : reconcileVnpayGroup(resolved.groupId(), context);
    }

    private PaymentReconciliationEntity applyQuery(CheckoutGroupEntity group, List<OrderEntity> children,
                                                    PaymentAttemptEntity attempt,
                                                    PaymentGatewayClient.QueryResult result,
                                                    RequestContext context) {
        String state = result == null || result.providerState() == null
                ? PaymentGatewayClient.UNKNOWN : result.providerState();
        if (PaymentGatewayClient.CAPTURED.equals(state)) {
            if (result.amount() == null || result.providerTransactionId() == null
                    || result.providerTransactionId().isBlank()) {
                return saveGroupReconciliation(group, attempt, state, result.providerTransactionId(),
                        result.amount(), PaymentReconciliationReason.QUERY_FAILED,
                        PaymentReconciliationResolution.OPEN, "Querydr capture thiếu số tiền hoặc mã giao dịch.");
            }
            VerifiedPaymentCallback callback = new VerifiedPaymentCallback("VNPAY", providerReference(attempt),
                    result.providerTransactionId(), null, result.amount(), true, result.responseCode(), "00",
                    result.message(), "reconcile:VNPAY:" + result.providerTransactionId(), Instant.now());
            PaymentCallbackResult applied = applyVerifiedLocked(callback, context,
                    payloadHash("VNPAY", callback.canonicalPayload()));
            PaymentReconciliationReason reason = applied.outcome() == PaymentCallbackOutcome.APPLIED
                    ? PaymentReconciliationReason.LOCAL_PENDING_PROVIDER_CAPTURED
                    : applied.outcome() == PaymentCallbackOutcome.LATE_CAPTURE
                        ? PaymentReconciliationReason.LOCAL_CLOSED_PROVIDER_CAPTURED
                        : applied.outcome() == PaymentCallbackOutcome.AMOUNT_MISMATCH
                            ? PaymentReconciliationReason.AMOUNT_MISMATCH : PaymentReconciliationReason.MATCHED;
            PaymentReconciliationResolution resolution = applied.outcome() == PaymentCallbackOutcome.APPLIED
                    ? PaymentReconciliationResolution.AUTO_CAPTURED
                    : applied.outcome() == PaymentCallbackOutcome.DUPLICATE
                        ? PaymentReconciliationResolution.NONE : PaymentReconciliationResolution.OPEN;
            return saveGroupReconciliation(group, attempt, state, result.providerTransactionId(),
                    result.amount(), reason, resolution, "Trạng thái VNPAY đã được kiểm tra qua querydr.");
        }
        if (PaymentGatewayClient.FAILED.equals(state) || PaymentGatewayClient.NOT_FOUND.equals(state)) {
            if (attempt.getStatus() != null && attempt.getStatus().isOpen()) {
                attempt.setStatus(PaymentAttemptStatus.FAILED);
                attempt.setFailureCode(limit(result.responseCode(), 40));
                attempt.setFailureReason(limit(result.message(), 500));
                attempt.setUpdatedAt(Instant.now());
                attempts.save(attempt);
            }
            if (group.getLifecycleStatus() == CheckoutGroupLifecycleStatus.PENDING
                    && group.getExpiresAt() != null && group.getExpiresAt().isAfter(Instant.now())
                    && group.getPaymentStatus() == CheckoutGroupPaymentStatus.PROCESSING) {
                group.setPaymentStatus(CheckoutGroupPaymentStatus.PENDING);
                groups.save(group);
            }
            return saveGroupReconciliation(group, attempt, state, result.providerTransactionId(), result.amount(),
                    PaymentReconciliationReason.MATCHED, PaymentReconciliationResolution.NONE,
                    "Cổng xác nhận phiên chưa thu tiền.");
        }
        group.setPaymentStatus(CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED);
        groups.save(group);
        return saveGroupReconciliation(group, attempt, state, result == null ? null : result.providerTransactionId(),
                result == null ? null : result.amount(), PaymentGatewayClient.PENDING.equals(state)
                        ? PaymentReconciliationReason.PROVIDER_PENDING : PaymentReconciliationReason.QUERY_FAILED,
                PaymentReconciliationResolution.OPEN,
                result == null || result.message() == null ? "Chưa đối soát được VNPAY." : result.message());
    }

    public boolean cancelUnpaidVnpayGroup(UUID actorId, UUID groupId, RequestContext context) {
        Prepared prepared = transaction.execute(status -> {
            CheckoutGroupEntity group = groups.findByIdForUpdate(groupId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhóm checkout."));
            if (actorId == null || !actorId.equals(group.getCustomerId())) {
                throw new ResourceNotFoundException("Không tìm thấy nhóm checkout.");
            }
            List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(groupId);
            if (!"VNPAY".equalsIgnoreCase(group.getPaymentMethod())
                    || group.getLifecycleStatus() != CheckoutGroupLifecycleStatus.PENDING
                    || group.getPaymentStatus() == CheckoutGroupPaymentStatus.PAID
                    || group.getPaymentStatus() == CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED
                    || group.getPaymentStatus() == CheckoutGroupPaymentStatus.CANCELLED
                    || group.getPaymentStatus() == CheckoutGroupPaymentStatus.EXPIRED
                    || !allocations.findByGroupIdOrderByCreatedAtAsc(groupId).isEmpty()) {
                return blockedPrepared(group, "Chỉ nhóm VNPAY chưa thanh toán và chưa đóng mới có thể hủy toàn bộ.");
            }
            PaymentAttemptEntity latest = attempts.findByCheckoutGroupIdForUpdateOrderByCreatedAtDesc(groupId)
                    .stream().filter(row -> row.getStatus() != null && row.getStatus().isOpen())
                    .findFirst().orElse(null);
            if (latest == null) return readyPrepared(group, null, null);
            PaymentGatewayClient gateway = gateway(latest.getProvider());
            if (gateway == null || latest.getProviderEnvironment() == null
                    || gateway.providerEnvironment() != latest.getProviderEnvironment()) {
                return processingPrepared(group, "Không thể xác minh phiên thanh toán đang mở; nhóm chưa được hủy.");
            }
            return queryRequired(group, latest, gateway, context);
        });
        if (prepared == null) return false;
        if (prepared.state() == PrepareState.QUERY_REQUIRED) {
            UUID activeAttemptId = prepared.attempt().getId();
            PaymentGatewayClient.QueryResult query;
            try {
                query = prepared.gateway().query(prepared.query());
            } catch (RuntimeException exception) {
                query = new PaymentGatewayClient.QueryResult(PaymentGatewayClient.QUERY_FAILED,
                        null, null, "HTTP", "Không thể xác minh phiên đang mở.", null);
            }
            PaymentGatewayClient.QueryResult result = query;
            prepared = transaction.execute(status -> {
                CheckoutGroupEntity group = groups.findByIdForUpdate(groupId).orElse(null);
                if (group == null) return blockedPrepared(null, "Không tìm thấy nhóm checkout.");
                List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(groupId);
                PaymentAttemptEntity attempt = attempts.findById(activeAttemptId).orElse(null);
                if (attempt == null) return blockedPrepared(group, "Không tìm thấy phiên VNPAY.");
                applyQuery(group, children, attempt, result, context);
                if (PaymentGatewayClient.FAILED.equals(result.providerState())
                        || PaymentGatewayClient.NOT_FOUND.equals(result.providerState())) {
                    return readyPrepared(group, null, null);
                }
                return processingPrepared(group, "Phiên VNPAY chưa được xác nhận thất bại; nhóm chưa được hủy.");
            });
        }
        if (prepared.state() == PrepareState.PROCESSING || prepared.state() == PrepareState.BLOCKED) {
            throw new BusinessConflictException(prepared.message() == null
                    ? "Nhóm thanh toán cần đối soát trước khi hủy." : prepared.message());
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            CheckoutGroupEntity group = groups.findByIdForUpdate(groupId).orElse(null);
            if (group == null) return false;
            List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(groupId);
            if (actorId == null || !actorId.equals(group.getCustomerId())) return false;
            if (group.getPaymentStatus() != CheckoutGroupPaymentStatus.PENDING
                    && group.getPaymentStatus() != CheckoutGroupPaymentStatus.PROCESSING) return false;
            if (group.getLifecycleStatus() != CheckoutGroupLifecycleStatus.PENDING
                    || !allocations.findByGroupIdOrderByCreatedAtAsc(groupId).isEmpty()) return false;
            cancelChildrenAndGroup(group, children, false, context);
            return true;
        }));
    }

    /** Finalizes a zero-due group from the placement transaction without creating a gateway capture. */
    public void finalizeZeroDueGroup(UUID actorId, UUID groupId, RequestContext context) {
        transaction.executeWithoutResult(status -> {
            CheckoutGroupEntity group = groups.findByIdForUpdate(groupId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhóm checkout."));
            if (actorId != null && !actorId.equals(group.getCustomerId())) {
                throw new ResourceNotFoundException("Không tìm thấy nhóm checkout.");
            }
            List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(groupId);
            if (group.getTotalAmount() != 0 || children.isEmpty() || sumChildTotals(children) != 0
                    || group.getLifecycleStatus() != CheckoutGroupLifecycleStatus.PENDING
                    || group.getPaymentStatus() != CheckoutGroupPaymentStatus.PENDING) {
                throw new BusinessConflictException("Chỉ nhóm đang chờ với tổng phải trả bằng 0 mới được chốt.");
            }
            if (!attempts.findByCheckoutGroupIdOrderByCreatedAtDesc(groupId).isEmpty()
                    || !transactions.findByCheckoutGroupIdOrderByCreatedAtAsc(groupId).isEmpty()
                    || !allocations.findByGroupIdOrderByCreatedAtAsc(groupId).isEmpty()) {
                throw new BusinessConflictException("Nhóm zero-due đã có bằng chứng gateway; không thể chốt tự động.");
            }
            Instant now = Instant.now();
            for (OrderEntity child : children) {
                if (!groupId.equals(child.getCheckoutGroupId()) || child.getTotalAmount() != 0
                        || child.getStatus() != OrderStatus.PENDING
                        || (child.getPaymentStatus() != PaymentStatus.PENDING
                            && child.getPaymentStatus() != PaymentStatus.PROCESSING)) {
                    throw new BusinessConflictException("Đơn con zero-due không còn ở trạng thái chờ.");
                }
                PaymentEntity projection = captureProjection(child);
                projection.setProvider("ZERO_DUE");
                projection.setProviderTransactionId(null);
                projection.setAmount(0);
                projection.setCurrency("VND");
                if (projection.getStatus() != PaymentStatus.SUCCEEDED) projection.transitionTo(PaymentStatus.SUCCEEDED);
                projection.setProcessedAt(now);
                projection.setFailureReason(null);
                payments.saveAndFlush(projection);
                int changed = orders.compareAndSetStatus(child.getId(), OrderStatus.PENDING.name(),
                        OrderStatus.PAID.name(), now);
                if (changed != 1) throw new BusinessConflictException("Đơn con đã thay đổi trong lúc chốt zero-due.");
                child.transitionTo(OrderStatus.PAID);
                child.setPaymentStatus(PaymentStatus.SUCCEEDED);
                child.setPaidAt(now);
                orders.save(child);
                if (shopFinancial != null) shopFinancial.recordSale(child);
                if (fulfillment != null) fulfillment.ensureForOrder(child.getId(), context);
                audit.record("ORDER_STATUS_CHANGED", actorId, "Order", child.getId(),
                        Map.of("from", OrderStatus.PENDING.name(), "to", OrderStatus.PAID.name(),
                                "paymentReason", "ZERO_DUE_AFTER_DISCOUNTS",
                                "checkoutGroupId", groupId.toString()), context);
            }
            group.setPaymentStatus(CheckoutGroupPaymentStatus.PAID);
            group.setLifecycleStatus(CheckoutGroupLifecycleStatus.CONFIRMED);
            groups.save(group);
            audit.record(AuditAction.PAYMENT_STATUS_CHANGED.name(), actorId, "CheckoutGroup", groupId,
                    Map.of("paymentReason", "ZERO_DUE_AFTER_DISCOUNTS", "orderCount", children.size()), context);
        });
    }

    public int expireDueGroups(Instant now, RequestContext context) {
        Instant effective = now == null ? Instant.now() : now;
        Integer expiredCount = transaction.execute(status -> {
            int count = 0;
            for (CheckoutGroupEntity group : groups.findPendingExpiredForUpdate(effective)) {
                CheckoutGroupEntity locked = groups.findByIdForUpdate(group.getId()).orElse(null);
                if (locked == null || locked.getExpiresAt() == null || locked.getExpiresAt().isAfter(effective)
                        || (locked.getPaymentStatus() != CheckoutGroupPaymentStatus.PENDING
                            && locked.getPaymentStatus() != CheckoutGroupPaymentStatus.PROCESSING)) continue;
                List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(locked.getId());
                if (expireGroupLocked(locked, children, effective, context)) count++;
            }
            return count;
        });
        return expiredCount == null ? 0 : expiredCount;
    }

    private boolean expireGroupLocked(CheckoutGroupEntity group, List<OrderEntity> children, Instant now,
                                      RequestContext context) {
        if (group == null || group.getExpiresAt() == null || group.getExpiresAt().isAfter(now)
                || group.getLifecycleStatus() != CheckoutGroupLifecycleStatus.PENDING
                || group.getPaymentStatus() == CheckoutGroupPaymentStatus.PAID
                || group.getPaymentStatus() == CheckoutGroupPaymentStatus.RECONCILIATION_REQUIRED
                || !allocations.findByGroupIdOrderByCreatedAtAsc(group.getId()).isEmpty()) return false;
        cancelChildrenAndGroup(group, children, true, context);
        return true;
    }

    private void cancelChildrenAndGroup(CheckoutGroupEntity group, List<OrderEntity> children,
                                        boolean expired, RequestContext context) {
        Instant now = Instant.now();
        for (OrderEntity child : children.stream().sorted(Comparator.comparing(OrderEntity::getId)).toList()) {
            if (!group.getId().equals(child.getCheckoutGroupId())) {
                throw new BusinessConflictException("Đơn con không thuộc nhóm checkout.");
            }
            if (child.getStatus() == OrderStatus.PENDING) {
                restoreStock(child.getId());
                if (discounts != null) discounts.release(child.getId());
                OrderStatus target = expired ? OrderStatus.EXPIRED : OrderStatus.CANCELLED;
                int changed = orders.compareAndSetStatus(child.getId(), OrderStatus.PENDING.name(), target.name(), now);
                if (changed != 1) throw new BusinessConflictException("Đơn con đã thay đổi trong lúc đóng nhóm.");
                child.transitionTo(target);
                child.setCancelledAt(now);
                child.setCancelReason(expired ? "PAYMENT_TIMEOUT" : "CHECKOUT_GROUP_CANCELLED");
            } else if (child.getStatus() != (expired ? OrderStatus.EXPIRED : OrderStatus.CANCELLED)) {
                throw new BusinessConflictException("Có đơn con không còn ở trạng thái chờ; không thể đóng nhóm.");
            }
            if (child.getPaymentStatus() == PaymentStatus.PENDING
                    || child.getPaymentStatus() == PaymentStatus.PROCESSING) {
                child.setPaymentStatus(PaymentStatus.FAILED);
            }
            for (PaymentEntity payment : payments.findByOrderIdForUpdateOrderByIdAsc(child.getId())) {
                if (payment.getCheckoutPaymentAllocationId() == null
                        && (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.PROCESSING)) {
                    payment.transitionTo(PaymentStatus.FAILED);
                    payment.setFailureReason(expired ? "PAYMENT_TIMEOUT" : "CHECKOUT_GROUP_CANCELLED");
                    payment.setProcessedAt(now);
                    payments.save(payment);
                }
            }
            orders.save(child);
            audit.record("ORDER_STATUS_CHANGED", null, "Order", child.getId(),
                    Map.of("from", OrderStatus.PENDING.name(), "to", child.getStatus().name(),
                            "checkoutGroupId", group.getId().toString()), context);
        }
        PaymentAttemptStatus attemptStatus = expired ? PaymentAttemptStatus.EXPIRED : PaymentAttemptStatus.FAILED;
        for (PaymentAttemptEntity attempt : attempts.findByCheckoutGroupIdForUpdateOrderByCreatedAtDesc(group.getId())) {
            if (attempt.getStatus() != null && attempt.getStatus().isOpen()) {
                attempt.setStatus(attemptStatus);
                attempt.setFailureCode(expired ? "PAYMENT_TIMEOUT" : "CHECKOUT_GROUP_CANCELLED");
                attempt.setFailureReason(expired ? "Checkout group expired before payment." : "Checkout group cancelled.");
                attempt.setUpdatedAt(now);
                attempts.save(attempt);
            }
        }
        group.setPaymentStatus(expired ? CheckoutGroupPaymentStatus.EXPIRED : CheckoutGroupPaymentStatus.CANCELLED);
        group.setLifecycleStatus(expired ? CheckoutGroupLifecycleStatus.EXPIRED : CheckoutGroupLifecycleStatus.CANCELLED);
        groups.save(group);
        audit.record("CHECKOUT_GROUP_CLOSED", null, "CheckoutGroup", group.getId(),
                Map.of("paymentStatus", group.getPaymentStatus().name(), "orderCount", children.size()), context);
    }

    public PaymentAttemptStatus vnpayAttemptStatus(UUID groupId) {
        return attempts.findByCheckoutGroupIdOrderByCreatedAtDesc(groupId).stream()
                .filter(row -> "VNPAY".equalsIgnoreCase(row.getProvider()))
                .map(PaymentAttemptEntity::getStatus).findFirst().orElse(null);
    }

    public boolean processRefundIntent(UUID transactionId, Instant staleBefore) {
        PaymentProviderTransactionEntity snapshot = transactions.findById(transactionId).orElse(null);
        if (snapshot == null || snapshot.getOrderId() != null || snapshot.getCheckoutGroupId() == null
                || snapshot.getTransactionType() != ProviderTransactionType.REFUND) return false;
        RefundPlan plan = transaction.execute(status -> {
            CheckoutGroupEntity group = groups.findByIdForUpdate(snapshot.getCheckoutGroupId()).orElse(null);
            if (group == null) return null;
            List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(group.getId());
            PaymentProviderTransactionEntity intent = transactions.findByIdForUpdate(snapshot.getId()).orElse(null);
            if (intent == null || intent.getOrderId() != null
                    || !group.getId().equals(intent.getCheckoutGroupId())
                    || intent.getTransactionType() != ProviderTransactionType.REFUND) return null;
            boolean pending = intent.getStatus() == ProviderTransactionStatus.PENDING;
            boolean stale = intent.getStatus() == ProviderTransactionStatus.PROCESSING
                    && intent.getProcessedAt() != null && !intent.getProcessedAt().isAfter(staleBefore);
            if (!pending && !stale) return null;
            PaymentAttemptEntity attempt = intent.getAttemptId() == null ? null
                    : attempts.findById(intent.getAttemptId()).orElse(null);
            String captureId = intent.getProviderRequestId();
            PaymentProviderTransactionEntity capture = transactions
                    .findByProviderAndProviderTransactionIdAndTransactionType(
                            intent.getProvider(), captureId, ProviderTransactionType.CAPTURE).orElse(null);
            PaymentGatewayClient gateway = gateway(intent.getProvider());
            if (attempt == null || capture == null || !group.getId().equals(capture.getCheckoutGroupId())
                    || capture.getStatus() != ProviderTransactionStatus.SUCCEEDED
                    || !Objects.equals(capture.getAttemptId(), attempt.getId())
                    || !Objects.equals(capture.getProviderTransactionId(), captureId)
                    || !Objects.equals(capture.getCurrency(), intent.getCurrency())
                    || capture.getAmount() != group.getTotalAmount()
                    || attempt.getOrderId() != null || !group.getId().equals(attempt.getCheckoutGroupId())
                    || !intent.getProvider().equalsIgnoreCase(attempt.getProvider())
                    || attempt.getAmount() != group.getTotalAmount()
                    || !Objects.equals(attempt.getCurrency(), capture.getCurrency())
                    || capture.getProviderEnvironment() != attempt.getProviderEnvironment()
                    || !Objects.equals(PaymentIntegrityService.returnResolutionId(intent.getIdempotencyKey()),
                            intent.getReturnResolutionId())
                    || gateway == null || attempt.getProviderEnvironment() == null
                    || gateway.providerEnvironment() != attempt.getProviderEnvironment()) return null;

            boolean lateCapture = intent.getIdempotencyKey() != null
                    && intent.getIdempotencyKey().startsWith("late:VNPAY:");
            CheckoutRefundAllocationEntity attribution = refundAllocations == null ? null
                    : refundAllocations.findByRefundTransactionId(intent.getId()).orElse(null);
            UUID orderId = null;
            UUID captureAllocationId = null;
            if (lateCapture) {
                if (attribution != null || !"VNPAY".equalsIgnoreCase(intent.getProvider())
                        || intent.getAmount() <= 0 || intent.getAmount() > capture.getAmount()) return null;
                long activeForCapture = activeGroupRefundsForProviderCapture(group.getId(), captureId);
                if (activeForCapture > capture.getAmount()) return null;
            } else {
                if (attribution == null || !group.getId().equals(attribution.getGroupId())
                        || !Objects.equals(captureId, capture.getProviderTransactionId())
                        || intent.getAmount() != attribution.getAmount()
                        || !Objects.equals(intent.getCurrency(), attribution.getCurrency())) return null;
                OrderEntity child = children.stream().filter(row -> attribution.getOrderId().equals(row.getId()))
                        .findFirst().orElse(null);
                CheckoutPaymentAllocationEntity allocation = allocations.findById(
                        attribution.getCaptureAllocationId()).orElse(null);
                if (child == null || allocation == null || !group.getId().equals(child.getCheckoutGroupId())
                        || !group.getCustomerId().equals(child.getUserId())
                        || !group.getId().equals(allocation.getGroupId())
                        || !child.getId().equals(allocation.getOrderId())
                        || !capture.getId().equals(allocation.getCaptureTransactionId())
                        || intent.getAmount() > allocation.getAmount()) return null;
                if (group.getPaymentStatus() != CheckoutGroupPaymentStatus.PAID
                        && group.getPaymentStatus() != CheckoutGroupPaymentStatus.PARTIALLY_REFUNDED
                        || child.getStatus() != OrderStatus.PAID) return null;
                List<CheckoutPaymentAllocationEntity> captureAllocations =
                        allocations.findByCaptureTransactionIdOrderByOrderIdAsc(capture.getId());
                long allocated = captureAllocations.stream().mapToLong(CheckoutPaymentAllocationEntity::getAmount)
                        .reduce(0L, Math::addExact);
                long activeForCapture = 0;
                for (CheckoutPaymentAllocationEntity childAllocation : captureAllocations) {
                    activeForCapture = Math.addExact(activeForCapture,
                            refundAllocations.sumActiveRefundAmountsByCaptureAllocationId(childAllocation.getId()));
                }
                if (captureAllocations.isEmpty() || allocated != capture.getAmount()
                        || activeForCapture > capture.getAmount()
                        || refundAllocations.sumActiveRefundAmountsByCaptureAllocationId(allocation.getId())
                            > allocation.getAmount()) return null;
                PaymentEntity projection = payments.findByCheckoutPaymentAllocationId(allocation.getId()).orElse(null);
                if (projection == null || !child.getId().equals(projection.getOrderId())) return null;
                if (projection.getStatus() != PaymentStatus.REFUND_PENDING) {
                    projection.transitionTo(PaymentStatus.REFUND_PENDING);
                }
                payments.save(projection);
                child.setPaymentStatus(PaymentStatus.REFUND_PENDING);
                orders.save(child);
                orderId = child.getId();
                captureAllocationId = allocation.getId();
            }
            intent.setStatus(ProviderTransactionStatus.PROCESSING);
            intent.setProcessedAt(Instant.now());
            transactions.save(intent);
            PaymentGatewayClient.RefundCommand command = new PaymentGatewayClient.RefundCommand(
                    providerReference(attempt), capture.getProviderTransactionId(), attempt.getProviderCreateDate(),
                    intent.getAmount(), capture.getAmount(), intent.getAmount() != capture.getAmount(),
                    intent.getIdempotencyKey(), "system", "127.0.0.1",
                    (lateCapture ? "Hoan capture muon cua nhom " : "Hoan don con cua nhom ")
                            + group.getGroupNumber());
            return new RefundPlan(intent.getId(), group.getId(), attempt.getId(), intent.getIdempotencyKey(),
                    gateway, command, orderId, captureAllocationId, lateCapture);
        });
        if (plan == null) return false;
        PaymentGatewayClient.RefundResult result;
        try {
            result = plan.gateway().refund(plan.command());
        } catch (RuntimeException exception) {
            transaction.executeWithoutResult(status -> {
                groups.findByIdForUpdate(plan.groupId()).orElse(null);
                PaymentProviderTransactionEntity intent = transactions.findById(plan.transactionId()).orElse(null);
                if (intent != null && intent.getStatus() == ProviderTransactionStatus.PROCESSING) {
                    intent.setStatus(ProviderTransactionStatus.PENDING);
                    intent.setProcessedAt(null);
                    intent.setResponseMessage("Provider result unknown; retain refund hold and retry with same key.");
                    transactions.save(intent);
                }
            });
            return false;
        }
        PaymentGatewayClient.RefundResult completed = result;
        return Boolean.TRUE.equals(transaction.execute(status -> {
            CheckoutGroupEntity group = groups.findByIdForUpdate(plan.groupId()).orElse(null);
            List<OrderEntity> children = orders.findByCheckoutGroupIdForUpdateOrderByIdAsc(plan.groupId());
            PaymentProviderTransactionEntity intent = transactions.findById(plan.transactionId()).orElse(null);
            if (group == null || intent == null) return false;
            if (intent.getStatus() == ProviderTransactionStatus.SUCCEEDED) return true;
            if (intent.getStatus() != ProviderTransactionStatus.PROCESSING) return false;
            if (!plan.lateCapture()) {
                RequestContext workerContext = new RequestContext(null, null, "refund-intent-job", "SYSTEM");
                ChildRefundPlan childPlan = new ChildRefundPlan(intent.getId(), group.getId(), plan.orderId(),
                        plan.captureAllocationId(), intent.getIdempotencyKey(), plan.gateway(), plan.command(),
                        null, workerContext);
                ChildRefundBooking booking = finishChildRefund(childPlan, completed);
                return booking.succeeded();
            }
            intent.setStatus(completed.success() ? ProviderTransactionStatus.SUCCEEDED : ProviderTransactionStatus.FAILED);
            intent.setProviderTransactionId(blankToNull(completed.providerTransactionId()));
            intent.setResponseCode(limit(completed.responseCode(), 40));
            intent.setResponseMessage(limit(completed.message(), 500));
            intent.setRawResponse(limit(completed.rawBody(), PAYLOAD_LIMIT));
            intent.setProcessedAt(Instant.now());
            transactions.save(intent);
            saveGroupReconciliation(group, attempts.findById(plan.attemptId()).orElse(null),
                    completed.success() ? PaymentGatewayClient.REFUND_PENDING : PaymentGatewayClient.REFUND_REJECTED,
                    completed.providerTransactionId(), intent.getAmount(),
                    completed.success() ? PaymentReconciliationReason.MATCHED : PaymentReconciliationReason.QUERY_FAILED,
                    completed.success() ? PaymentReconciliationResolution.AUTO_REFUND_SUCCEEDED
                            : PaymentReconciliationResolution.AUTO_REFUND_FAILED,
                    completed.message() == null ? "Late capture refund was processed." : completed.message());
            if (audit != null) {
                audit.record("CHECKOUT_GROUP_LATE_CAPTURE_REFUND", null, "CheckoutGroup", group.getId(),
                        Map.of("refundStatus", intent.getStatus().name(), "captureTransactionId",
                                intent.getProviderRequestId(), "amount", intent.getAmount()),
                        new RequestContext(null, null, "refund-intent-job", "SYSTEM"));
            }
            return completed.success();
        }));
    }

    private long activeGroupRefundsForProviderCapture(UUID groupId, String captureId) {
        if (captureId == null || captureId.isBlank()) return 0;
        return transactions.findByCheckoutGroupIdOrderByCreatedAtAsc(groupId).stream()
                .filter(row -> row.getTransactionType() == ProviderTransactionType.REFUND
                        && Objects.equals(captureId, row.getProviderRequestId())
                        && (row.getStatus() == ProviderTransactionStatus.PENDING
                            || row.getStatus() == ProviderTransactionStatus.PROCESSING
                            || row.getStatus() == ProviderTransactionStatus.SUCCEEDED))
                .mapToLong(PaymentProviderTransactionEntity::getAmount).reduce(0L, Math::addExact);
    }

    public void recordGroupRejection(String reference, String canonicalPayload, String responseCode,
                                     String detail) {
        String hash = payloadHash("VNPAY", canonicalPayload);
        transaction.executeWithoutResult(status -> {
            if (callbacks.findByProviderAndPayloadSha256("VNPAY", hash).isPresent()) return;
            PaymentAttemptEntity attempt = attempts.findByProviderAndProviderTxnRef("VNPAY", reference).orElse(null);
            UUID groupId = attempt == null ? null : attempt.getCheckoutGroupId();
            PaymentCallbackEntity row = callbackRow(groupId, attempt, "VNPAY", reference, null,
                    true, PaymentCallbackOutcome.CONFLICT, responseCode, "00", null, hash,
                    canonicalPayload, Instant.now());
            callbacks.saveAndFlush(row);
        });
    }

    private PaymentCallbackResult recordGroupCallback(UUID groupId, PaymentAttemptEntity attempt,
                                                      VerifiedPaymentCallback callback, String hash,
                                                      PaymentCallbackOutcome outcome, String code,
                                                      String message, boolean signatureValid) {
        PaymentCallbackEntity row = callbackRow(groupId, attempt, callback.provider(),
                callback.orderNumber(), callback.providerTransactionId(), signatureValid, outcome, code,
                callback.transactionStatus(), callback.amount(), hash, callback.canonicalPayload(), receivedAt(callback));
        callbacks.saveAndFlush(row);
        return new PaymentCallbackResult(code, message, outcome);
    }

    private PaymentReconciliationEntity saveGroupReconciliation(CheckoutGroupEntity group,
                                                                PaymentAttemptEntity attempt,
                                                                String providerStatus, String transactionId,
                                                                Long amount, PaymentReconciliationReason reason,
                                                                PaymentReconciliationResolution resolution,
                                                                String detail) {
        PaymentReconciliationEntity row = new PaymentReconciliationEntity();
        row.setCheckoutGroupId(group == null ? null : group.getId());
        row.setAttemptId(attempt == null ? null : attempt.getId());
        row.setProvider(attempt == null ? "VNPAY" : attempt.getProvider());
        row.setMerchantReference(attempt == null ? group == null ? "UNKNOWN" : group.getGroupNumber()
                : attempt.getMerchantReference());
        row.setLocalStatus(group == null ? "UNKNOWN" : group.getPaymentStatus().name() + "/" + group.getLifecycleStatus());
        row.setProviderStatus(limit(providerStatus, 30));
        row.setProviderTransactionId(limit(transactionId, 190));
        row.setProviderAmount(amount);
        row.setReason(reason);
        row.setResolution(resolution);
        row.setDetail(limit(detail, 500));
        row.setCheckedAt(Instant.now());
        return reconciliations.save(row);
    }

    private PaymentProviderTransactionEntity groupTransaction(CheckoutGroupEntity group,
                                                               PaymentAttemptEntity attempt, String provider,
                                                               String providerTransactionId,
                                                               ProviderTransactionType type, long amount,
                                                               ProviderTransactionStatus status, String key,
                                                               String responseCode, String message, String raw,
                                                               Instant now) {
        PaymentProviderTransactionEntity row = new PaymentProviderTransactionEntity();
        row.setCheckoutGroupId(group.getId());
        row.setAttemptId(attempt == null ? null : attempt.getId());
        row.setPaymentId(null);
        row.setProvider(provider);
        row.setProviderEnvironment(attempt == null ? null : attempt.getProviderEnvironment());
        row.setProviderTransactionId(blankToNull(providerTransactionId));
        row.setProviderRequestId(key);
        row.setTransactionType(type);
        row.setAmount(amount);
        row.setCurrency(group.getCurrency() == null ? "VND" : group.getCurrency());
        row.setStatus(status);
        row.setIdempotencyKey(key);
        row.setResponseCode(limit(responseCode, 40));
        row.setResponseMessage(limit(message, 500));
        row.setRawResponse(limit(raw, PAYLOAD_LIMIT));
        row.setCreatedAt(now);
        row.setProcessedAt(now);
        return row;
    }

    private static PaymentCallbackEntity callbackRow(UUID groupId, PaymentAttemptEntity attempt,
                                                     String provider, String reference, String transactionId,
                                                     boolean signatureValid, PaymentCallbackOutcome outcome,
                                                     String responseCode, String transactionStatus,
                                                     Long amount, String hash, String payload, Instant receivedAt) {
        PaymentCallbackEntity row = new PaymentCallbackEntity();
        row.setCheckoutGroupId(groupId);
        row.setAttemptId(attempt == null ? null : attempt.getId());
        row.setProvider(provider);
        row.setProviderTransactionId(limit(transactionId, 190));
        row.setSignatureValid(signatureValid);
        row.setOutcome(outcome);
        row.setResponseCode(limit(responseCode, 40));
        row.setTransactionStatus(limit(transactionStatus, 40));
        row.setAmount(amount);
        row.setPayloadSha256(hash);
        row.setRawPayload(limit(payload == null ? "" : payload, PAYLOAD_LIMIT));
        row.setReceivedAt(receivedAt == null ? Instant.now() : receivedAt);
        return row;
    }

    private void restoreStock(UUID orderId) {
        for (OrderItemEntity item : orderItems.findByOrderIdOrderByIdAsc(orderId).stream()
                .sorted(Comparator.comparing(OrderItemEntity::getProductId)).toList()) {
            ProductEntity product = products.findByIdForUpdateIncludingInactive(item.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm để hoàn tồn kho."));
            product.setStockQuantity(Math.addExact(product.getStockQuantity(), item.getQuantity()));
            products.save(product);
        }
    }

    private PaymentGatewayClient gateway(String provider) {
        return gateways.stream().filter(client -> client.providerCode().equalsIgnoreCase(provider))
                .findFirst().orElse(null);
    }

    private PaymentProviderEnvironment providerEnvironment(String provider) {
        PaymentGatewayClient gateway = gateway(provider);
        return gateway == null ? null : gateway.providerEnvironment();
    }

    private static long sumChildTotals(List<OrderEntity> children) {
        return children.stream().mapToLong(OrderEntity::getTotalAmount).reduce(0L, Math::addExact);
    }

    private static String defaultOrderInfo(CheckoutGroupEntity group) {
        return "Thanh toan nhom " + group.getGroupNumber();
    }

    private static SessionResult ready(CheckoutGroupEntity group, VnpayGroupSession session) {
        return new SessionResult(SessionState.READY, group.getId(), group.getGroupNumber(),
                session.amount(), session.currency(), session.expiresAt(), session, null);
    }

    private static SessionResult processing(CheckoutGroupEntity group, String message) {
        return new SessionResult(SessionState.PROCESSING, group == null ? null : group.getId(),
                group == null ? null : group.getGroupNumber(), group == null ? 0 : group.getTotalAmount(),
                group == null ? "VND" : group.getCurrency(), group == null ? null : group.getExpiresAt(),
                null, message);
    }

    private static SessionResult blocked(CheckoutGroupEntity group, String message) {
        return new SessionResult(SessionState.BLOCKED, group == null ? null : group.getId(),
                group == null ? null : group.getGroupNumber(), group == null ? 0 : group.getTotalAmount(),
                group == null ? "VND" : group.getCurrency(), group == null ? null : group.getExpiresAt(),
                null, message);
    }

    private static Prepared readyPrepared(CheckoutGroupEntity group, PaymentAttemptEntity attempt,
                                          VnpayGroupSession session) {
        return new Prepared(PrepareState.READY, group, attempt, null, null, session, null);
    }

    private static Prepared processingPrepared(CheckoutGroupEntity group, String message) {
        return new Prepared(PrepareState.PROCESSING, group, null, null, null, null, message);
    }

    private static Prepared blockedPrepared(CheckoutGroupEntity group, String message) {
        return new Prepared(PrepareState.BLOCKED, group, null, null, null, null, message);
    }

    private static String providerReference(PaymentAttemptEntity attempt) {
        return attempt == null ? null : blankToNull(attempt.getProviderTxnRef()) == null
                ? attempt.getMerchantReference() : attempt.getProviderTxnRef();
    }

    private static String newVnpayReference(String groupNumber) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        String prefix = "G" + (groupNumber == null ? "WT" : groupNumber);
        int maxPrefix = 80 - suffix.length() - 1;
        if (prefix.length() > maxPrefix) prefix = prefix.substring(0, maxPrefix);
        return prefix + "-" + suffix;
    }

    private static String transactionKey(String source) {
        if (source.length() <= 190) return source;
        return source.substring(0, 120) + ":" + payloadHash(source, "");
    }

    private static String lateRefundKey(String providerTransactionId) {
        String key = "late:VNPAY:" + providerTransactionId;
        return key.length() <= 190 ? key : "late:VNPAY:" + payloadHash("VNPAY", providerTransactionId);
    }

    private static PaymentCallbackResult replay(PaymentCallbackEntity row) {
        return switch (row.getOutcome()) {
            case APPLIED -> new PaymentCallbackResult("00", "Payment Success", PaymentCallbackOutcome.DUPLICATE);
            case DUPLICATE, PAYMENT_FAILED, IGNORED -> new PaymentCallbackResult("00",
                    "Order already confirmed", PaymentCallbackOutcome.DUPLICATE);
            case LATE_CAPTURE -> new PaymentCallbackResult("00", "Late capture acknowledged", PaymentCallbackOutcome.DUPLICATE);
            case AMOUNT_MISMATCH -> new PaymentCallbackResult("04", "Invalid amount", PaymentCallbackOutcome.AMOUNT_MISMATCH);
            case REJECTED_SIGNATURE -> new PaymentCallbackResult("97", "Invalid signature", PaymentCallbackOutcome.REJECTED_SIGNATURE);
            case ORDER_NOT_FOUND -> new PaymentCallbackResult("01", "Order not found", PaymentCallbackOutcome.ORDER_NOT_FOUND);
            case CONFLICT -> new PaymentCallbackResult("02", "Order already confirmed", PaymentCallbackOutcome.CONFLICT);
        };
    }

    private static String payloadHash(String provider, String payload) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(((provider == null ? "" : provider)
                    + "\n" + (payload == null ? "" : payload)).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static Instant receivedAt(VerifiedPaymentCallback callback) {
        return callback.receivedAt() == null ? Instant.now() : callback.receivedAt();
    }

    private static String ip(RequestContext context) {
        return context == null || context.ipAddress() == null || context.ipAddress().isBlank()
                ? "127.0.0.1" : safeIp(context.ipAddress());
    }

    private static String safeIp(String value) {
        if (value == null || value.isBlank()) return "127.0.0.1";
        String trimmed = value.trim();
        return trimmed.length() <= 45 ? trimmed : "127.0.0.1";
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String safeText(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String limit(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }
}
