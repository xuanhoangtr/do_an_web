package com.utewedding.app.service.payment;

import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentAttemptStatus;
import com.utewedding.app.domain.PaymentCallbackOutcome;
import com.utewedding.app.domain.PaymentReconciliationReason;
import com.utewedding.app.domain.PaymentReconciliationResolution;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.ProviderTransactionStatus;
import com.utewedding.app.domain.ProviderTransactionType;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderItemEntity;
import com.utewedding.app.domain.entity.PaymentAttemptEntity;
import com.utewedding.app.domain.entity.PaymentCallbackEntity;
import com.utewedding.app.domain.entity.PaymentEntity;
import com.utewedding.app.domain.entity.PaymentProviderTransactionEntity;
import com.utewedding.app.domain.entity.PaymentReconciliationEntity;
import com.utewedding.app.domain.entity.ProductEntity;
import com.utewedding.app.dto.request.PaymentRequest;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
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
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.port.DiscountPolicy;
import com.utewedding.app.service.impl.AccountingDocumentService;
import com.utewedding.app.domain.AccountingDocumentType;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Payment attempts, callbacks, timeouts, provider refunds, and reconciliation.
 * Order status changes use the same compare-and-set as the order service.
 */
@Service
public class PaymentIntegrityService {
    public enum RefundApplication { APPLIED, REPLAY }

    public record VnpaySession(java.util.UUID attemptId, String providerReference, long amount,
                               String providerCreateDate, Instant expiresAt,
                               String orderInfo, String clientIp) { }

    public record VnpayAttemptReference(java.util.UUID orderId, String orderNumber,
                                        java.util.UUID attemptId, String providerReference,
                                        PaymentAttemptStatus status) { }

    private enum VnpayDecisionState { READY, QUERY_REQUIRED, BLOCKED }

    private record VnpayDecision(VnpayDecisionState state, VnpaySession session,
                                 java.util.UUID attemptId, String message) { }

    private static final Logger log = LoggerFactory.getLogger(PaymentIntegrityService.class);
    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final int PAYLOAD_LIMIT = 4000;

    private final PaymentOrderLock orderLock;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PaymentProviderTransactionRepository transactions;
    private final PaymentCallbackRepository callbacks;
    private final PaymentReconciliationRepository reconciliations;
    private final OrderItemRepository orderItems;
    private final ProductRepository products;
    private final DiscountPolicy discounts;
    private final FulfillmentService fulfillmentService;
    private final AuditLogPort auditLog;
    private final List<PaymentGatewayClient> gateways;
    private final TransactionTemplate independent;
    private AccountingDocumentService accountingDocuments;
    private com.utewedding.app.repository.ReturnCaseRepository returnCases;

    public PaymentIntegrityService(PaymentOrderLock orderLock, OrderRepository orders, PaymentRepository payments,
                                   PaymentAttemptRepository attempts,
                                   PaymentProviderTransactionRepository transactions,
                                   PaymentCallbackRepository callbacks,
                                   PaymentReconciliationRepository reconciliations,
                                   OrderItemRepository orderItems, ProductRepository products,
                                   @Qualifier("voucherDiscountPolicy") DiscountPolicy discounts,
                                   FulfillmentService fulfillmentService, AuditLogPort auditLog,
                                   List<PaymentGatewayClient> gateways,
                                   PlatformTransactionManager transactionManager) {
        this.orderLock = orderLock;
        this.orders = orders;
        this.payments = payments;
        this.attempts = attempts;
        this.transactions = transactions;
        this.callbacks = callbacks;
        this.reconciliations = reconciliations;
        this.orderItems = orderItems;
        this.products = products;
        this.discounts = discounts;
        this.fulfillmentService = fulfillmentService;
        this.auditLog = auditLog;
        this.gateways = gateways == null ? List.of() : List.copyOf(gateways);
        if (transactionManager == null) {
            this.independent = null;
        } else {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.independent = template;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachAccountingDocuments(AccountingDocumentService service) { this.accountingDocuments = service; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void attachReturnCases(com.utewedding.app.repository.ReturnCaseRepository repository) {
        this.returnCases = repository;
    }

    /**
     * Reuses an active session or creates a new immutable one. Expired sessions
     * are queried outside the order lock before a replacement can be created.
     */
    public VnpaySession createOrReuseVnpaySession(java.util.UUID orderId, String orderInfo,
                                                  String clientIp, int expireMinutes,
                                                  RequestContext context) {
        VnpayDecision first = orderLock.withOrderId(orderId, order ->
                inspectOrCreateVnpaySession(order, orderInfo, clientIp, expireMinutes));
        if (first.state() != VnpayDecisionState.QUERY_REQUIRED) return requireVnpaySession(first);

        PaymentAttemptEntity expiredAttempt = attempts.findById(first.attemptId()).orElse(null);
        if (expiredAttempt == null) {
            throw new BusinessConflictException("Đang xác minh thanh toán. Vui lòng kiểm tra lại sau.");
        }
        PaymentGatewayClient gateway = gateway("VNPAY");
        if (gateway == null) {
            throw new BusinessConflictException("Đang xác minh thanh toán. Vui lòng kiểm tra lại sau.");
        }
        PaymentGatewayClient.QueryResult query;
        try {
            query = gateway.query(new PaymentGatewayClient.QueryCommand(
                    providerReference(expiredAttempt), expiredAttempt.getProviderCreateDate(), ip(context)));
        } catch (RuntimeException exception) {
            query = new PaymentGatewayClient.QueryResult(PaymentGatewayClient.QUERY_FAILED,
                    null, null, "HTTP", "Không thể xác minh thanh toán lúc này.", null);
        }
        final PaymentGatewayClient.QueryResult verifiedQuery = query;
        VnpayDecision afterQuery = orderLock.withOrderId(orderId, order -> {
            if (order == null) return blocked("Không tìm thấy đơn hàng.");
            PaymentAttemptEntity currentAttempt = attempts.findById(first.attemptId()).orElse(null);
            if (currentAttempt == null) return blocked("Đang xác minh thanh toán. Vui lòng kiểm tra lại sau.");
            applyQuery(order, currentAttempt, verifiedQuery, context);
            if (order.getStatus() != OrderStatus.PENDING
                    || order.getPaymentStatus() != null && order.getPaymentStatus().isCaptured()) {
                return blocked("Thanh toán của đơn hàng đã được ghi nhận hoặc đơn không còn hiệu lực.");
            }
            String state = verifiedQuery.providerState() == null
                    ? PaymentGatewayClient.UNKNOWN : verifiedQuery.providerState();
            if (!PaymentGatewayClient.FAILED.equals(state) && !PaymentGatewayClient.NOT_FOUND.equals(state)) {
                return blocked("Đang xác minh thanh toán. Vui lòng kiểm tra lại sau.");
            }
            return inspectOrCreateVnpaySession(order, orderInfo, clientIp, expireMinutes);
        });
        return requireVnpaySession(afterQuery);
    }

    private VnpayDecision inspectOrCreateVnpaySession(OrderEntity order, String orderInfo,
                                                       String clientIp, int expireMinutes) {
        if (order == null) return blocked("Không tìm thấy đơn hàng.");
        if (order.getPaymentStatus() != null && order.getPaymentStatus().isCaptured()) {
            return blocked("Thanh toán của đơn hàng đã được ghi nhận.");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            return blocked("Đơn hàng không còn ở trạng thái chờ thanh toán.");
        }
        Instant now = Instant.now();
        if (order.getExpiresAt() == null || !order.getExpiresAt().isAfter(now)) {
            if (order.getExpiresAt() != null) expireLocked(order, now, null);
            return blocked("Đơn hàng đã hết hạn thanh toán. Vui lòng tạo đơn hàng mới.");
        }
        PaymentAttemptEntity latest = latestAttempt(order, "VNPAY");
        if (latest != null && latest.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
            return blocked("Thanh toán của đơn hàng đã được ghi nhận.");
        }
        if (latest != null && latest.getStatus() != null && latest.getStatus().isOpen()) {
            if (latest.getExpiresAt() != null && latest.getExpiresAt().isAfter(now)) {
                String reference = providerReference(latest);
                String savedInfo = blankToNull(latest.getProviderOrderInfo());
                String savedIp = blankToNull(latest.getProviderClientIp());
                String effectiveInfo = savedInfo == null
                        ? limit(orderInfo == null ? "Thanh toan don " + order.getOrderNumber() : orderInfo, 255)
                        : savedInfo;
                String effectiveIp = savedIp == null ? safeIp(clientIp) : savedIp;
                return ready(new VnpaySession(latest.getId(), reference, latest.getAmount(),
                        latest.getProviderCreateDate(), latest.getExpiresAt(), effectiveInfo, effectiveIp));
            }
            return new VnpayDecision(VnpayDecisionState.QUERY_REQUIRED, null, latest.getId(), null);
        }
        return createVnpaySessionLocked(order, orderInfo, clientIp, expireMinutes, now);
    }

    private VnpayDecision createVnpaySessionLocked(OrderEntity order, String orderInfo,
                                                    String clientIp, int expireMinutes, Instant now) {
        Instant orderExpiry = order.getExpiresAt();
        long sessionSeconds = Math.max(60L, Math.multiplyExact((long) Math.max(1, expireMinutes), 60L));
        Instant sessionExpiry = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(sessionSeconds);
        Instant orderBound = orderExpiry.minusSeconds(30);
        if (orderBound.isBefore(sessionExpiry)) sessionExpiry = orderBound;
        sessionExpiry = sessionExpiry.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        if (sessionExpiry.isBefore(now.plusSeconds(60))) {
            return blocked("Đơn hàng còn quá ít thời gian để tạo phiên thanh toán mới. Vui lòng tạo đơn hàng mới.");
        }
        String createDate = VN_DATE.format(now.atZone(ZoneId.of("Asia/Ho_Chi_Minh")));
        String reference = newVnpayReference(order.getOrderNumber());
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setOrderId(order.getId());
        attempt.setProvider("VNPAY");
        attempt.setMerchantReference(reference);
        attempt.setProviderTxnRef(reference);
        attempt.setProviderCreateDate(createDate);
        attempt.setProviderOrderInfo(limit(orderInfo == null
                ? "Thanh toan don " + order.getOrderNumber() : orderInfo, 255));
        attempt.setProviderClientIp(safeIp(clientIp));
        attempt.setAmount(order.getTotalAmount());
        attempt.setCurrency(currency(order));
        attempt.setStatus(PaymentAttemptStatus.PROCESSING);
        attempt.setExpiresAt(sessionExpiry);
        attempt.setCreatedAt(now);
        attempt.setUpdatedAt(now);
        attempt = attempts.saveAndFlush(attempt);

        PaymentEntity sessionPayment = null;
        for (PaymentEntity payment : payments.findByOrderIdForUpdateOrderByIdAsc(order.getId())) {
            if ("VNPAY".equalsIgnoreCase(payment.getProvider()) && payment.getAttemptId() == null
                    && (payment.getStatus() == PaymentStatus.PENDING
                    || payment.getStatus() == PaymentStatus.PROCESSING)) {
                sessionPayment = payment;
                break;
            }
        }
        if (sessionPayment == null) {
            sessionPayment = new PaymentEntity();
            sessionPayment.setOrderId(order.getId());
            sessionPayment.setProvider("VNPAY");
            sessionPayment.setAmount(order.getTotalAmount());
            sessionPayment.setCurrency(currency(order));
            sessionPayment.setStatus(PaymentStatus.PENDING);
        }
        sessionPayment.setAttemptId(attempt.getId());
        if (sessionPayment.getStatus() == PaymentStatus.PENDING) {
            sessionPayment.transitionTo(PaymentStatus.PROCESSING);
        }
        payments.save(sessionPayment);
        if (order.getPaymentStatus() == PaymentStatus.PENDING) {
            applyOrderPayment(order, PaymentStatus.PROCESSING);
            orders.save(order);
        }
        return ready(new VnpaySession(attempt.getId(), reference, attempt.getAmount(),
                createDate, sessionExpiry, attempt.getProviderOrderInfo(), attempt.getProviderClientIp()));
    }

    private VnpaySession requireVnpaySession(VnpayDecision decision) {
        if (decision.state() == VnpayDecisionState.READY) return decision.session();
        throw new BusinessConflictException(decision.message() == null
                ? "Đang xác minh thanh toán. Vui lòng kiểm tra lại sau." : decision.message());
    }

    private static VnpayDecision ready(VnpaySession session) {
        return new VnpayDecision(VnpayDecisionState.READY, session, null, null);
    }

    private static VnpayDecision blocked(String message) {
        return new VnpayDecision(VnpayDecisionState.BLOCKED, null, null, message);
    }

    private static String providerReference(PaymentAttemptEntity attempt) {
        return attempt == null ? null : blankToNull(attempt.getProviderTxnRef()) == null
                ? attempt.getMerchantReference() : attempt.getProviderTxnRef();
    }

    private static String newVnpayReference(String orderNumber) {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase(Locale.ROOT);
        String prefix = orderNumber == null ? "WT" : orderNumber;
        int maxPrefix = 80 - suffix.length() - 1;
        if (prefix.length() > maxPrefix) prefix = prefix.substring(0, maxPrefix);
        return prefix + "-" + suffix;
    }

    private static String safeIp(String value) {
        if (value == null || value.isBlank()) return "127.0.0.1";
        String trimmed = value.trim();
        return trimmed.length() <= 45 ? trimmed : "127.0.0.1";
    }

    @Transactional(rollbackFor = Exception.class)
    public void registerAttempt(OrderEntity order, String provider, long amount, Instant expiresAt,
                                String providerCreateDate) {
        if (order == null || order.getId() == null || provider == null) return;
        PaymentAttemptEntity open = openAttempt(order, provider);
        Instant now = Instant.now();
        if (open == null) {
            open = new PaymentAttemptEntity();
            open.setOrderId(order.getId());
            open.setProvider(provider);
            open.setMerchantReference(order.getOrderNumber());
            open.setAmount(amount);
            open.setCurrency(currency(order));
            open.setStatus(PaymentAttemptStatus.PROCESSING);
            open.setCreatedAt(now);
        }
        open.setExpiresAt(expiresAt);
        if (providerCreateDate != null && !providerCreateDate.isBlank()) {
            open.setProviderCreateDate(providerCreateDate);
        }
        open.setUpdatedAt(now);
        attempts.save(open);
        for (PaymentEntity payment : payments.findByOrderIdForUpdateOrderByIdAsc(order.getId())) {
            if (provider.equalsIgnoreCase(payment.getProvider()) && payment.getStatus() == PaymentStatus.PENDING) {
                payment.transitionTo(PaymentStatus.PROCESSING);
                payments.save(payment);
            }
        }
        if (order.getPaymentStatus() == PaymentStatus.PENDING) {
            applyOrderPayment(order, PaymentStatus.PROCESSING);
            orders.save(order);
        }
    }

    public PaymentCallbackResult recordInvalidSignature(String provider, String canonicalPayload,
                                                        String orderNumber, String responseCode) {
        String hash = hash(provider, canonicalPayload);
        PaymentCallbackEntity existing = callbacks.findByProviderAndPayloadSha256(provider, hash).orElse(null);
        if (existing != null) return replay(existing);
        PaymentCallbackEntity row = callbackRow(provider, null, null, null, false,
                PaymentCallbackOutcome.REJECTED_SIGNATURE, responseCode, null, null, hash, canonicalPayload, Instant.now());
        insertIsolated(row);
        return new PaymentCallbackResult("97", "Invalid signature", PaymentCallbackOutcome.REJECTED_SIGNATURE);
    }

    public PaymentCallbackResult applyVerified(VerifiedPaymentCallback callback, RequestContext context) {
        String hash = hash(callback.provider(), callback.canonicalPayload());
        if ("VNPAY".equalsIgnoreCase(callback.provider())) {
            PaymentAttemptEntity matched = attempts.findByProviderAndProviderTxnRef(
                    callback.provider(), callback.orderNumber()).orElse(null);
            if (matched != null) {
                return orderLock.withOrderId(matched.getOrderId(), order -> applyVerifiedLocked(
                        order, callback, context, hash));
            }
        }
        return orderLock.withOrderNumber(callback.orderNumber(), order -> {
            return applyVerifiedLocked(order, callback, context, hash);
        });
    }

    private PaymentCallbackResult applyVerifiedLocked(OrderEntity order, VerifiedPaymentCallback callback,
                                                       RequestContext context, String hash) {
        PaymentCallbackEntity existing = callbacks.findByProviderAndPayloadSha256(callback.provider(), hash)
                .orElse(null);
        if (existing != null) return replay(existing);
        Decision decision = decide(order, callback, context);
        PaymentCallbackEntity row = callbackRow(callback.provider(), order == null ? null : order.getId(),
                decision.attemptId(), callback.providerTransactionId(), true, decision.outcome(),
                callback.responseCode(), callback.transactionStatus(), callback.amount(), hash,
                callback.canonicalPayload(), receivedAt(callback));
        callbacks.save(row);
        return decision.result();
    }

    public int expireDue(Instant now, RequestContext context) {
        Instant effective = now == null ? Instant.now() : now;
        return orderLock.withExpiredOrders(effective, order -> expireLocked(order, effective, context));
    }

    /** Closes open attempts after cancel or timeout. The caller already holds the order lock. */
    public void noteOrderClosed(OrderEntity order, String reason, Instant now) {
        if (order == null || order.getId() == null) return;
        PaymentAttemptStatus attemptStatus = "PAYMENT_TIMEOUT".equals(reason)
                ? PaymentAttemptStatus.EXPIRED : PaymentAttemptStatus.FAILED;
        failOpenPayments(order, reason, now);
        failOpenAttempts(order, attemptStatus, reason, now);
    }

    public String onlineProvider(OrderEntity order) {
        if (order == null) return null;
        if (order.getPaymentMethod() != null) {
            String method = order.getPaymentMethod().trim().toUpperCase(Locale.ROOT);
            if ("VNPAY".equals(method) || "MOMO".equals(method)) return method;
        }
        if (order.getId() == null) return null;
        for (PaymentEntity row : payments.findByOrderIdOrderByCreatedAtDesc(order.getId())) {
            if (row.getProvider() == null || row.getStatus() == null || !row.getStatus().isCaptured()) continue;
            String provider = row.getProvider().trim().toUpperCase(Locale.ROOT);
            if ("VNPAY".equals(provider) || "MOMO".equals(provider)) return provider;
        }
        return null;
    }

    public RefundApplication refund(java.util.UUID actorId, java.util.UUID orderId, String provider,
                                    PaymentRequest request, RequestContext context) {
        return refund(actorId, orderId, provider, request, context, false);
    }

    public RefundApplication refundReturn(java.util.UUID actorId, java.util.UUID orderId, String provider,
                                          PaymentRequest request, RequestContext context) {
        return refund(actorId, orderId, provider, request, context, true);
    }

    private RefundApplication refund(java.util.UUID actorId, java.util.UUID orderId, String provider,
                                     PaymentRequest request, RequestContext context, boolean allowReturnCase) {
        String key = normalizeKey(request == null ? null : request.providerTransactionId());
        RefundPreparation preparation = orderLock.withOrderId(orderId, order ->
                prepareRefundIntent(order, provider, key, request == null ? null : request.amount(),
                        actorId, context, false, true, allowReturnCase));
        if (preparation.application() != null) return preparation.application();
        return executeRefund(preparation.plan());
    }

    /** Retries committed refund intents after a timeout or late capture, always with the same provider key. */
    public int processPendingRefundIntents(Instant staleBefore, int limit) {
        int processed = 0;
        for (java.util.UUID id : transactions.findRefundIntentsDue(staleBefore, Math.max(1, limit))) {
            try {
                if (processRefundIntent(id, staleBefore)) processed++;
            } catch (RuntimeException exception) {
                log.warn("Refund intent {} remains pending after provider processing error", id);
            }
        }
        return processed;
    }

    private boolean processRefundIntent(java.util.UUID transactionId, Instant staleBefore) {
        PaymentProviderTransactionEntity snapshot = transactions.findById(transactionId).orElse(null);
        if (snapshot == null) return false;
        RefundPreparation preparation = orderLock.withOrderId(snapshot.getOrderId(), order -> {
            if (order == null) return RefundPreparation.none();
            PaymentProviderTransactionEntity intent = transactions.findByIdempotencyKeyForUpdate(
                    snapshot.getIdempotencyKey()).orElse(null);
            if (intent == null || intent.getTransactionType() != ProviderTransactionType.REFUND) {
                return RefundPreparation.none();
            }
            boolean duePending = intent.getStatus() == ProviderTransactionStatus.PENDING;
            boolean staleProcessing = intent.getStatus() == ProviderTransactionStatus.PROCESSING
                    && intent.getProcessedAt() != null && !intent.getProcessedAt().isAfter(staleBefore);
            if (!duePending && !staleProcessing) return RefundPreparation.none();
            return claimRefundIntent(order, intent, null,
                    new RequestContext(null, null, "refund-intent-job", "SYSTEM"));
        });
        if (preparation.plan() == null) return false;
        executeRefund(preparation.plan());
        return true;
    }

    private RefundPreparation prepareRefundIntent(OrderEntity order, String provider, String key, Long amount,
                                                  java.util.UUID actorId, RequestContext context,
                                                  boolean lateClose, boolean dispatchNow,
                                                  boolean allowReturnCase) {
        if (order == null) throw new ResourceNotFoundException("Không tìm thấy đơn hàng.");
        PaymentProviderTransactionEntity existing = transactions.findByIdempotencyKeyForUpdate(key).orElse(null);
        if (existing != null) {
            validateRefundIntent(order, existing, provider, amount);
            if (existing.getStatus() == ProviderTransactionStatus.SUCCEEDED) {
                return RefundPreparation.replay();
            }
            if (existing.getStatus() == ProviderTransactionStatus.FAILED) {
                throw new BusinessConflictException("Yêu cầu hoàn tiền này đã thất bại. Dùng mã mới để thử lại.");
            }
            if (existing.getStatus() == ProviderTransactionStatus.PROCESSING) {
                throw new BusinessConflictException("Yêu cầu hoàn tiền này đang được xử lý.");
            }
            return claimRefundIntent(order, existing, actorId, context);
        }

        if (!lateClose && order.getStatus() != OrderStatus.PAID) {
            throw new BusinessConflictException("Đơn hàng không ở trạng thái có thể hoàn tiền.");
        }
        if (!lateClose && !allowReturnCase && returnCases != null
                && returnCases.existsByOrderIdAndStatusIn(order.getId(),
                java.util.EnumSet.of(
                        com.utewedding.app.domain.ReturnCaseStatus.REQUESTED,
                        com.utewedding.app.domain.ReturnCaseStatus.UNDER_REVIEW,
                        com.utewedding.app.domain.ReturnCaseStatus.APPROVED,
                        com.utewedding.app.domain.ReturnCaseStatus.AWAITING_RETURN,
                        com.utewedding.app.domain.ReturnCaseStatus.RETURN_IN_TRANSIT,
                        com.utewedding.app.domain.ReturnCaseStatus.RECEIVED,
                        com.utewedding.app.domain.ReturnCaseStatus.INSPECTED,
                        com.utewedding.app.domain.ReturnCaseStatus.RESOLUTION_PENDING,
                        com.utewedding.app.domain.ReturnCaseStatus.REFUND_PENDING,
                        com.utewedding.app.domain.ReturnCaseStatus.REFUND_FAILED,
                        com.utewedding.app.domain.ReturnCaseStatus.EXCHANGE_PENDING,
                        com.utewedding.app.domain.ReturnCaseStatus.DISPUTED,
                        com.utewedding.app.domain.ReturnCaseStatus.RESOLVED,
                        com.utewedding.app.domain.ReturnCaseStatus.CLOSED))) {
            throw new BusinessConflictException("Đơn có hồ sơ trả hàng; hãy hoàn tiền qua resolution của hồ sơ.");
        }
        PaymentEntity payment = capturePayment(order, provider);
        if (payment == null || payment.getProviderTransactionId() == null
                || payment.getProviderTransactionId().isBlank()) {
            throw new BusinessConflictException("Không tìm thấy giao dịch cổng để hoàn tiền.");
        }
        PaymentGatewayClient gateway = gateway(provider);
        if (gateway == null) throw new BusinessConflictException("Cổng thanh toán chưa sẵn sàng để hoàn tiền.");

        long remaining = Math.subtractExact(payment.getAmount(), payment.getRefundedAmount());
        long reserved = transactions.findByOrderIdOrderByCreatedAtAsc(order.getId()).stream()
                .filter(row -> row.getTransactionType() == ProviderTransactionType.REFUND
                        && payment.getId().equals(row.getPaymentId())
                        && (row.getStatus() == ProviderTransactionStatus.PENDING
                        || row.getStatus() == ProviderTransactionStatus.PROCESSING))
                .mapToLong(PaymentProviderTransactionEntity::getAmount).reduce(0L, Math::addExact);
        long requested = amount == null ? Math.subtractExact(remaining, reserved) : amount;
        if (requested <= 0 || requested > Math.subtractExact(remaining, reserved)) {
            throw new BusinessConflictException("Số tiền hoàn không hợp lệ hoặc đã được giữ bởi yêu cầu khác.");
        }
        PaymentAttemptEntity attempt = attemptForPayment(order, payment, provider);
        Instant now = Instant.now();
        PaymentProviderTransactionEntity intent = transaction(order, attempt, payment, provider, null,
                ProviderTransactionType.REFUND, requested, ProviderTransactionStatus.PENDING,
                key, null, "Refund intent recorded", null, now);
        java.util.UUID returnResolutionId = returnResolutionId(key);
        if (returnResolutionId != null) intent.setReturnResolutionId(returnResolutionId);
        intent.setProcessedAt(null);
        intent = transactions.saveAndFlush(intent);
        reserveRefundPaymentState(order, payment, lateClose);
        if (dispatchNow) {
            intent.setStatus(ProviderTransactionStatus.PROCESSING);
            intent.setProcessedAt(now);
        }
        return RefundPreparation.plan(refundPlan(order, payment, attempt, intent, gateway, actorId, context,
                lateClose));
    }

    private RefundPreparation claimRefundIntent(OrderEntity order, PaymentProviderTransactionEntity intent,
                                                java.util.UUID actorId, RequestContext context) {
        PaymentEntity payment = intent.getPaymentId() == null ? null
                : payments.findById(intent.getPaymentId()).orElse(null);
        if (payment == null) throw new BusinessConflictException("Refund intent thiếu giao dịch thanh toán.");
        PaymentAttemptEntity attempt = intent.getAttemptId() == null ? null
                : attempts.findById(intent.getAttemptId()).orElse(null);
        PaymentGatewayClient gateway = gateway(intent.getProvider());
        if (gateway == null) throw new BusinessConflictException("Cổng thanh toán chưa sẵn sàng để hoàn tiền.");
        intent.setStatus(ProviderTransactionStatus.PROCESSING);
        intent.setProcessedAt(Instant.now());
        java.util.UUID returnResolutionId = returnResolutionId(intent.getIdempotencyKey());
        if (returnResolutionId != null) intent.setReturnResolutionId(returnResolutionId);
        boolean lateClose = intent.getIdempotencyKey().startsWith("late:");
        reserveRefundPaymentState(order, payment, lateClose);
        return RefundPreparation.plan(refundPlan(order, payment, attempt, intent, gateway, actorId, context,
                lateClose));
    }

    private RefundPlan refundPlan(OrderEntity order, PaymentEntity payment, PaymentAttemptEntity attempt,
                                  PaymentProviderTransactionEntity intent, PaymentGatewayClient gateway,
                                  java.util.UUID actorId, RequestContext context, boolean lateClose) {
        boolean partial = intent.getAmount() != payment.getAmount() || payment.getRefundedAmount() > 0;
        PaymentGatewayClient.RefundCommand command = new PaymentGatewayClient.RefundCommand(
                attempt == null ? order.getOrderNumber() : providerReference(attempt),
                payment.getProviderTransactionId(), createDate(order, payment, attempt),
                intent.getAmount(), payment.getAmount(), partial, intent.getIdempotencyKey(),
                actorId == null ? "system" : actorId.toString(), ip(context), "Hoan tien " + order.getOrderNumber());
        return new RefundPlan(order.getId(), intent.getIdempotencyKey(), intent.getProvider(), command,
                intent.getAmount(), actorId, context, lateClose, gateway);
    }

    private void reserveRefundPaymentState(OrderEntity order, PaymentEntity payment, boolean lateClose) {
        if (payment.getStatus() != PaymentStatus.REFUND_PENDING) {
            payment.transitionTo(PaymentStatus.REFUND_PENDING);
        }
        payments.save(payment);
        if (lateClose) order.setPaymentStatus(PaymentStatus.REFUND_PENDING);
        else applyOrderPayment(order, PaymentStatus.REFUND_PENDING);
        orders.save(order);
    }

    private RefundApplication executeRefund(RefundPlan plan) {
        PaymentGatewayClient.RefundResult result;
        try {
            result = plan.gateway().refund(plan.command());
        } catch (RuntimeException exception) {
            orderLock.withOrderId(plan.orderId(), order -> {
                PaymentProviderTransactionEntity intent = transactions.findByIdempotencyKeyForUpdate(plan.key())
                        .orElse(null);
                if (intent != null && intent.getStatus() == ProviderTransactionStatus.PROCESSING) {
                    intent.setStatus(ProviderTransactionStatus.PENDING);
                    intent.setProcessedAt(null);
                    intent.setResponseMessage("Provider result is unknown; retry with the same idempotency key.");
                }
                return null;
            });
            throw new BusinessConflictException("Kết quả hoàn tiền đang chờ xác nhận từ cổng; hãy thử lại bằng cùng mã.");
        }
        RefundBooking booking = orderLock.withOrderId(plan.orderId(), order ->
                finishRefund(order, plan, result));
        if (!booking.succeeded() && !plan.lateClose()) {
            throw new BusinessConflictException(result.message() == null || result.message().isBlank()
                    ? "Cổng thanh toán từ chối hoàn tiền."
                    : "Cổng thanh toán từ chối hoàn tiền: " + result.message());
        }
        return booking.application();
    }

    private RefundBooking finishRefund(OrderEntity order, RefundPlan plan,
                                      PaymentGatewayClient.RefundResult result) {
        if (order == null) throw new ResourceNotFoundException("Không tìm thấy đơn hàng.");
        PaymentProviderTransactionEntity intent = transactions.findByIdempotencyKeyForUpdate(plan.key())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy refund intent."));
        if (intent.getStatus() == ProviderTransactionStatus.SUCCEEDED) {
            return new RefundBooking(true, RefundApplication.REPLAY, "Yêu cầu hoàn tiền đã được xử lý.");
        }
        if (intent.getStatus() != ProviderTransactionStatus.PROCESSING) {
            throw new BusinessConflictException("Refund intent đã được xử lý bởi một thao tác khác.");
        }
        PaymentEntity payment = payments.findById(intent.getPaymentId())
                .orElseThrow(() -> new BusinessConflictException("Không tìm thấy giao dịch thanh toán."));
        PaymentAttemptEntity attempt = intent.getAttemptId() == null ? null
                : attempts.findById(intent.getAttemptId()).orElse(null);
        Instant now = Instant.now();
        if (!result.success()) {
            intent.setStatus(ProviderTransactionStatus.FAILED);
            intent.setProviderTransactionId(blankToNull(result.providerTransactionId()));
            intent.setResponseCode(limit(result.responseCode(), 40));
            intent.setResponseMessage(limit(result.message(), 500));
            intent.setRawResponse(limit(result.rawBody(), PAYLOAD_LIMIT));
            intent.setProcessedAt(now);
            if (!hasOtherPendingRefund(order.getId(), plan.key())) {
                if (payment.getStatus() != PaymentStatus.REFUND_FAILED) payment.transitionTo(PaymentStatus.REFUND_FAILED);
                if (plan.lateClose()) order.setPaymentStatus(PaymentStatus.REFUND_FAILED);
                else applyOrderPayment(order, PaymentStatus.REFUND_FAILED);
            }
            payments.save(payment);
            orders.save(order);
            if (plan.lateClose()) updateLateCaptureReconciliation(order, intent.getAttemptId(),
                    PaymentReconciliationResolution.AUTO_REFUND_FAILED,
                    result.message());
            return new RefundBooking(false, RefundApplication.APPLIED,
                    result.message() == null ? "Cổng thanh toán từ chối hoàn tiền." : result.message());
        }

        long refunded = Math.addExact(payment.getRefundedAmount(), intent.getAmount());
        if (refunded > payment.getAmount()) throw new BusinessConflictException("Tổng hoàn vượt số tiền đã thu.");
        payment.setRefundedAmount(refunded);
        intent.setStatus(ProviderTransactionStatus.SUCCEEDED);
        intent.setProviderTransactionId(blankToNull(result.providerTransactionId()));
        intent.setResponseCode(limit(result.responseCode(), 40));
        intent.setResponseMessage(limit(result.message(), 500));
        intent.setRawResponse(limit(result.rawBody(), PAYLOAD_LIMIT));
        intent.setProcessedAt(now);
        boolean anotherPending = hasOtherPendingRefund(order.getId(), plan.key());
        OrderStatus previousOrder = order.getStatus();
        PaymentStatus previousPayment = order.getPaymentStatus();
        if (refunded >= payment.getAmount()) {
            if (payment.getStatus() != PaymentStatus.REFUNDED) payment.transitionTo(PaymentStatus.REFUNDED);
            if (plan.lateClose()) order.setPaymentStatus(PaymentStatus.REFUNDED);
            else {
                if (!cas(order, OrderStatus.REFUNDED, now)) {
                    throw new BusinessConflictException("Đơn hàng đã được cập nhật bởi một thao tác khác.");
                }
                applyOrderPayment(order, PaymentStatus.REFUNDED);
            }
        } else if (anotherPending) {
            payment.setStatus(PaymentStatus.REFUND_PENDING);
            order.setPaymentStatus(PaymentStatus.REFUND_PENDING);
        } else {
            if (payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED)
                payment.transitionTo(PaymentStatus.PARTIALLY_REFUNDED);
            if (plan.lateClose()) order.setPaymentStatus(PaymentStatus.PARTIALLY_REFUNDED);
            else applyOrderPayment(order, PaymentStatus.PARTIALLY_REFUNDED);
        }
        payment.setProcessedAt(now);
        payments.save(payment);
        orders.save(order);
        if (accountingDocuments != null) {
            accountingDocuments.ensure(order, AccountingDocumentType.CREDIT_NOTE,
                    result.providerTransactionId() == null ? plan.key() : result.providerTransactionId(),
                    intent.getAmount());
        }
        if (!plan.lateClose() && previousOrder != order.getStatus()) {
            auditLog.record("ORDER_STATUS_CHANGED", plan.actorId(), "Order", order.getId(),
                    Map.of("from", previousOrder.name(), "to", order.getStatus().name(),
                            "provider", plan.provider()), plan.context());
        }
        auditLog.record("PAYMENT_STATUS_CHANGED", plan.actorId(), "Payment", payment.getId(),
                Map.of("status", String.valueOf(previousPayment) + " -> " + order.getPaymentStatus().name(),
                        "provider", plan.provider(), "amount", intent.getAmount()), plan.context());
        if (plan.lateClose()) updateLateCaptureReconciliation(order, intent.getAttemptId(),
                PaymentReconciliationResolution.AUTO_REFUND_SUCCEEDED, "Provider refund succeeded.");
        return new RefundBooking(true, RefundApplication.APPLIED, "Đã hoàn tiền qua cổng thanh toán.");
    }

    private boolean hasOtherPendingRefund(java.util.UUID orderId, String key) {
        return transactions.findByOrderIdOrderByCreatedAtAsc(orderId).stream()
                .anyMatch(row -> row.getTransactionType() == ProviderTransactionType.REFUND
                        && !key.equals(row.getIdempotencyKey())
                        && (row.getStatus() == ProviderTransactionStatus.PENDING
                        || row.getStatus() == ProviderTransactionStatus.PROCESSING));
    }

    private void validateRefundIntent(OrderEntity order, PaymentProviderTransactionEntity intent,
                                      String provider, Long requestedAmount) {
        if (!order.getId().equals(intent.getOrderId())
                || intent.getTransactionType() != ProviderTransactionType.REFUND
                || !provider.equalsIgnoreCase(intent.getProvider())
                || requestedAmount != null && requestedAmount != intent.getAmount()) {
            throw new BusinessConflictException("Khóa hoàn tiền đã được dùng cho nội dung khác.");
        }
    }

    private void updateLateCaptureReconciliation(OrderEntity order, java.util.UUID attemptId,
                                                PaymentReconciliationResolution resolution,
                                                String detail) {
        reconciliations.findFirstByOrderIdAndAttemptIdAndReasonAndResolutionOrderByCheckedAtDesc(order.getId(),
                attemptId, PaymentReconciliationReason.LOCAL_CLOSED_PROVIDER_CAPTURED,
                PaymentReconciliationResolution.OPEN).ifPresent(row -> {
            row.setResolution(resolution);
            row.setDetail(limit(detail, 500));
            row.setCheckedAt(Instant.now());
        });
    }

    public PaymentReconciliationEntity reconcileOrder(java.util.UUID orderId, RequestContext context) {
        java.util.UUID attemptId = orderLock.withOrderId(orderId, order -> {
            if (order == null) throw new ResourceNotFoundException("Không tìm thấy đơn hàng.");
            PaymentAttemptEntity attempt = latestOnlineAttempt(order);
            if (attempt == null) {
                saveReconciliation(order, null, "NONE", null, null, null,
                        PaymentReconciliationReason.QUERY_FAILED, PaymentReconciliationResolution.OPEN,
                        "Không có attempt VNPay hoặc MoMo.");
                return null;
            }
            return attempt.getId();
        });
        if (attemptId == null) {
            return reconciliations.findFirstByOrderIdAndReasonAndResolutionOrderByCheckedAtDesc(orderId,
                    PaymentReconciliationReason.QUERY_FAILED, PaymentReconciliationResolution.OPEN).orElse(null);
        }
        return reconcileAttempt(orderId, attemptId, context);
    }

    public PaymentReconciliationEntity reconcileVnpayOrder(java.util.UUID orderId, RequestContext context) {
        java.util.UUID attemptId = orderLock.withOrderId(orderId, order -> {
            if (order == null) throw new ResourceNotFoundException("Không tìm thấy đơn hàng.");
            if (order.getStatus() == OrderStatus.PAID
                    || order.getPaymentStatus() != null && order.getPaymentStatus().isCaptured()) return null;
            return attempts.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                    .filter(row -> "VNPAY".equalsIgnoreCase(row.getProvider()))
                    .filter(row -> row.getStatus() != null && row.getStatus().isOpen())
                    .map(PaymentAttemptEntity::getId)
                    .findFirst().orElse(null);
        });
        return attemptId == null ? null : reconcileAttempt(orderId, attemptId, context);
    }

    /** Resolve only an immutable VNPAY attempt reference, including legacy order-number references. */
    public VnpayAttemptReference resolveVnpayReference(String reference) {
        if (reference == null || reference.isBlank()) return null;
        PaymentAttemptEntity attempt = attempts.findByProviderAndProviderTxnRef("VNPAY", reference).orElse(null);
        if (attempt == null) {
            OrderEntity legacyOrder = orders.findByOrderNumber(reference).orElse(null);
            if (legacyOrder == null) return null;
            attempt = attempts.findByOrderIdOrderByCreatedAtDesc(legacyOrder.getId()).stream()
                    .filter(row -> "VNPAY".equalsIgnoreCase(row.getProvider()))
                    .filter(row -> row.getProviderTxnRef() == null || row.getProviderTxnRef().isBlank())
                    .filter(row -> reference.equals(row.getMerchantReference()))
                    .findFirst().orElse(null);
        }
        if (attempt == null || attempt.getOrderId() == null
                || !"VNPAY".equalsIgnoreCase(attempt.getProvider())) return null;
        OrderEntity order = orders.findById(attempt.getOrderId()).orElse(null);
        if (order == null) return null;
        String providerReference = providerReference(attempt);
        if (!reference.equals(providerReference)) return null;
        return new VnpayAttemptReference(order.getId(), order.getOrderNumber(), attempt.getId(),
                providerReference, attempt.getStatus());
    }

    public PaymentReconciliationEntity reconcileVnpayReference(String reference, RequestContext context) {
        VnpayAttemptReference resolved = resolveVnpayReference(reference);
        if (resolved == null) return null;
        return reconcileAttempt(resolved.orderId(), resolved.attemptId(), context);
    }

    public PaymentAttemptStatus vnpayAttemptStatus(java.util.UUID orderId) {
        return attempts.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .filter(row -> "VNPAY".equalsIgnoreCase(row.getProvider()))
                .map(PaymentAttemptEntity::getStatus)
                .findFirst().orElse(null);
    }

    private PaymentReconciliationEntity reconcileAttempt(java.util.UUID orderId, java.util.UUID attemptId,
                                                          RequestContext context) {
        ReconcilePreparation preparation = orderLock.withOrderId(orderId, order -> {
            if (order == null) throw new ResourceNotFoundException("Không tìm thấy đơn hàng.");
            PaymentAttemptEntity attempt = attempts.findById(attemptId).orElse(null);
            if (attempt == null) {
                return ReconcilePreparation.immediate(saveReconciliation(order, null, local(order),
                        PaymentGatewayClient.QUERY_FAILED, null, null, PaymentReconciliationReason.QUERY_FAILED,
                        PaymentReconciliationResolution.OPEN, "Không tìm thấy phiên thanh toán."));
            }
            PaymentGatewayClient gateway = gateway(attempt.getProvider());
            if (gateway == null) {
                return ReconcilePreparation.immediate(saveReconciliation(order, attempt, attempt.getStatus().name(), PaymentGatewayClient.QUERY_FAILED,
                        attempt.getProviderTransactionId(), null, PaymentReconciliationReason.QUERY_FAILED,
                        PaymentReconciliationResolution.OPEN, "Chưa có cổng thanh toán."));
            }
            return ReconcilePreparation.query(attempt.getId(), gateway,
                    new PaymentGatewayClient.QueryCommand(providerReference(attempt),
                            attempt.getProviderCreateDate(), ip(context)));
        });
        if (preparation.immediate() != null) return preparation.immediate();
        PaymentGatewayClient.QueryResult result = preparation.gateway().query(preparation.command());
        return orderLock.withOrderId(orderId, order -> {
            if (order == null) throw new ResourceNotFoundException("Không tìm thấy đơn hàng.");
            PaymentAttemptEntity attempt = attempts.findById(preparation.attemptId()).orElse(null);
            if (attempt == null) {
                return saveReconciliation(order, null, local(order), PaymentGatewayClient.QUERY_FAILED,
                        null, null, PaymentReconciliationReason.QUERY_FAILED,
                        PaymentReconciliationResolution.OPEN, "Payment attempt disappeared during reconciliation.");
            }
            return applyQuery(order, attempt, result, context);
        });
    }

    public int reconcileStaleAttempts(Instant updatedBefore, int limit) {
        List<PaymentAttemptEntity> stale = attempts.findByStatusInAndUpdatedAtBefore(
                List.of(PaymentAttemptStatus.PENDING, PaymentAttemptStatus.PROCESSING), updatedBefore,
                PageRequest.of(0, Math.max(1, limit)));
        int count = 0;
        for (PaymentAttemptEntity attempt : stale) {
            try {
                reconcileAttempt(attempt.getOrderId(), attempt.getId(), null);
                count++;
            } catch (RuntimeException exception) {
                log.warn("Payment reconciliation failed for order {}", attempt.getOrderId());
            }
        }
        return count;
    }

    public String orderNumberForVnpayReference(String reference) {
        VnpayAttemptReference resolved = resolveVnpayReference(reference);
        return resolved == null ? null : resolved.orderNumber();
    }

    private Decision decide(OrderEntity order, VerifiedPaymentCallback callback, RequestContext context) {
        if (order == null) {
            return decided("01", "Order not found", PaymentCallbackOutcome.ORDER_NOT_FOUND, null);
        }
        if ("MOMO".equalsIgnoreCase(callback.provider())
                && (callback.providerRequestId() == null || !callback.providerRequestId().equals(order.getOrderNumber()))) {
            return decided("04", "Invalid request", PaymentCallbackOutcome.CONFLICT, null);
        }
        if (callback.amount() == null || callback.amount() != order.getTotalAmount()) {
            return decided("04", "Invalid amount", PaymentCallbackOutcome.AMOUNT_MISMATCH, null);
        }
        if (callback.success() && (callback.providerTransactionId() == null || callback.providerTransactionId().isBlank())) {
            return decided("04", "Invalid transaction", PaymentCallbackOutcome.CONFLICT, null);
        }
        if (callback.providerTransactionId() != null && ownedByAnotherOrder(order, callback)) {
            return decided("04", "Transaction already belongs to another order", PaymentCallbackOutcome.CONFLICT, null);
        }
        PaymentAttemptEntity callbackAttempt = attemptFor(order, callback);
        if (callbackAttempt.getAmount() != callback.amount()) {
            return decided("04", "Invalid amount", PaymentCallbackOutcome.AMOUNT_MISMATCH, callbackAttempt.getId());
        }
        if (callback.success() && captureExists(callback.provider(), callback.providerTransactionId(), order)) {
            return decided("00", "Order already confirmed", PaymentCallbackOutcome.DUPLICATE, null);
        }
        Instant now = receivedAt(callback);
        if (order.getStatus() == OrderStatus.PENDING && order.getExpiresAt() != null
                && !order.getExpiresAt().isAfter(now)) {
            expireLocked(order, now, context);
        }
        if (order.getStatus() == OrderStatus.EXPIRED || order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.REFUNDED) {
            if (!callback.success()) {
                return decided("00", "Transaction was not successful", PaymentCallbackOutcome.IGNORED, null);
            }
            PaymentAttemptEntity attempt = callbackAttempt;
            lateCapture(order, attempt, callback, context);
            return decided("00", "Late capture acknowledged", PaymentCallbackOutcome.LATE_CAPTURE, attempt.getId());
        }
        if (order.getStatus() == OrderStatus.PAID || (order.getPaymentStatus() != null && order.getPaymentStatus().isCaptured())) {
            if (callback.success()) {
                PaymentAttemptEntity attempt = callbackAttempt;
                lateCapture(order, attempt, callback, context);
                return decided("00", "Late capture acknowledged", PaymentCallbackOutcome.LATE_CAPTURE, attempt.getId());
            }
            return decided("00", "Transaction was not successful", PaymentCallbackOutcome.IGNORED, null);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            return decided("02", "Order is not payable", PaymentCallbackOutcome.CONFLICT, null);
        }
        PaymentAttemptEntity attempt = callbackAttempt;
        if (!callback.success()) {
            attempt.setStatus(PaymentAttemptStatus.FAILED);
            attempt.setFailureCode(limit(callback.responseCode(), 40));
            attempt.setFailureReason(limit(callback.message(), 500));
            attempt.setProviderTransactionId(blankToNull(callback.providerTransactionId()));
            attempt.setUpdatedAt(now);
            attempts.save(attempt);
            PaymentEntity payment = editablePayment(order, callback.provider(), attempt);
            if (payment != null && (payment.getStatus() == PaymentStatus.PENDING
                    || payment.getStatus() == PaymentStatus.PROCESSING)) {
                payment.setFailureReason(limit(callback.message() == null ? callback.responseCode() : callback.message(), 500));
                payments.save(payment);
            }
            return decided("00", "Transaction was not successful", PaymentCallbackOutcome.PAYMENT_FAILED, attempt.getId());
        }
        if (!cas(order, OrderStatus.PAID, now)) {
            return decided("02", "Order already confirmed", PaymentCallbackOutcome.CONFLICT, attempt.getId());
        }
        capture(order, attempt, callback, now, context);
        return decided("00", "Payment Success", PaymentCallbackOutcome.APPLIED, attempt.getId());
    }

    private void capture(OrderEntity order, PaymentAttemptEntity attempt, VerifiedPaymentCallback callback,
                         Instant now, RequestContext context) {
        PaymentStatus previousPayment = order.getPaymentStatus();
        OrderStatus previousOrder = OrderStatus.PENDING;
        applyOrderPayment(order, PaymentStatus.SUCCEEDED);
        order.setPaidAt(now);
        orders.save(order);
        PaymentEntity payment = editablePayment(order, callback.provider(), attempt);
        if (payment == null) {
            payment = new PaymentEntity();
            payment.setOrderId(order.getId());
            payment.setProvider(callback.provider());
            payment.setAmount(callback.amount());
            payment.setCurrency(currency(order));
            payment.setStatus(PaymentStatus.SUCCEEDED);
        } else if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            payment.transitionTo(PaymentStatus.SUCCEEDED);
        }
        payment.setProvider(callback.provider());
        payment.setAttemptId(attempt.getId());
        payment.setProviderTransactionId(callback.providerTransactionId());
        payment.setAmount(callback.amount());
        payment.setProcessedAt(now);
        payment.setFailureReason(null);
        payments.save(payment);
        if (accountingDocuments != null) {
            accountingDocuments.ensure(order, AccountingDocumentType.PAYMENT_RECEIPT,
                    callback.providerTransactionId());
        }
        attempt.setStatus(PaymentAttemptStatus.SUCCEEDED);
        attempt.setProviderTransactionId(callback.providerTransactionId());
        attempt.setFailureCode(null);
        attempt.setFailureReason(null);
        attempt.setUpdatedAt(now);
        attempts.save(attempt);
        saveTransaction(order, attempt, payment, callback.provider(), callback.providerTransactionId(),
                ProviderTransactionType.CAPTURE, callback.amount(), ProviderTransactionStatus.SUCCEEDED,
                "capture:" + callback.provider() + ":" + callback.providerTransactionId(),
                callback.responseCode(), callback.message(), null, now);
        auditLog.record("ORDER_STATUS_CHANGED", null, "Order", order.getId(),
                Map.of("from", previousOrder.name(), "to", order.getStatus().name(), "provider", callback.provider()),
                context);
        auditLog.record("PAYMENT_STATUS_CHANGED", null, "Payment", payment.getId(),
                Map.of("status", String.valueOf(previousPayment) + " -> " + payment.getStatus().name(),
                        "provider", callback.provider()), context);
        if (fulfillmentService != null) fulfillmentService.ensureForOrder(order.getId(), context);
    }

    private void lateCapture(OrderEntity order, PaymentAttemptEntity attempt, VerifiedPaymentCallback callback,
                             RequestContext context) {
        Instant now = receivedAt(callback);
        PaymentEntity payment = editablePayment(order, callback.provider(), attempt);
        if (payment == null || payment.getStatus() == null || payment.getStatus().isCaptured()
                && !callback.providerTransactionId().equals(payment.getProviderTransactionId())) {
            payment = new PaymentEntity();
            payment.setOrderId(order.getId());
            payment.setProvider(callback.provider());
            payment.setAmount(callback.amount());
            payment.setCurrency(currency(order));
            payment.setStatus(PaymentStatus.FAILED);
        }
        if (payment.getStatus() != PaymentStatus.SUCCEEDED && !payment.getStatus().isCaptured()) {
            payment.acceptLateCapture();
        }
        payment.setProvider(callback.provider());
        payment.setAttemptId(attempt.getId());
        payment.setProviderTransactionId(callback.providerTransactionId());
        payment.setProcessedAt(now);
        payments.save(payment);
        attempt.setStatus(PaymentAttemptStatus.SUCCEEDED);
        attempt.setProviderTransactionId(callback.providerTransactionId());
        attempt.setUpdatedAt(now);
        attempts.save(attempt);
        String captureKey = "capture:" + callback.provider() + ":" + callback.providerTransactionId();
        if (transactions.findByIdempotencyKey(captureKey).isEmpty()) {
            saveTransaction(order, attempt, payment, callback.provider(), callback.providerTransactionId(),
                    ProviderTransactionType.CAPTURE, callback.amount(), ProviderTransactionStatus.SUCCEEDED,
                    captureKey, callback.responseCode(), callback.message(), null, now);
        }
        String lateKey = lateKey(callback.provider(), callback.providerTransactionId());
        queueLateRefundIntent(order, attempt, payment, callback, lateKey, now);
        saveReconciliation(order, attempt, order.getStatus().name(), PaymentGatewayClient.CAPTURED,
                callback.providerTransactionId(), callback.amount(),
                PaymentReconciliationReason.LOCAL_CLOSED_PROVIDER_CAPTURED,
                PaymentReconciliationResolution.OPEN, "Late or duplicate capture recorded; refund intent queued.");
    }

    private void queueLateRefundIntent(OrderEntity order, PaymentAttemptEntity attempt, PaymentEntity payment,
                                      VerifiedPaymentCallback callback, String key, Instant now) {
        if (transactions.findByIdempotencyKey(key).isPresent()) return;
        PaymentGatewayClient gateway = gateway(callback.provider());
        PaymentProviderTransactionEntity intent = transaction(order, attempt, payment, callback.provider(), null,
                ProviderTransactionType.REFUND, callback.amount(), ProviderTransactionStatus.PENDING,
                key, null, gateway == null ? "Late capture needs manual reconciliation"
                        : "Late capture refund intent recorded", null, now);
        intent.setProcessedAt(null);
        transactions.saveAndFlush(intent);
        if (payment.getStatus() != PaymentStatus.REFUND_PENDING) {
            payment.transitionTo(PaymentStatus.REFUND_PENDING);
        }
        payments.save(payment);
        order.setPaymentStatus(PaymentStatus.REFUND_PENDING);
        orders.save(order);
    }

    private boolean expireLocked(OrderEntity order, Instant now, RequestContext context) {
        if (order.getStatus() != OrderStatus.PENDING) return false;
        if (order.getExpiresAt() == null || order.getExpiresAt().isAfter(now)) return false;
        if (order.getPaymentStatus() != null && order.getPaymentStatus().isCaptured()) return false;
        restoreStock(order.getId());
        if (discounts != null) discounts.release(order.getId());
        if (!cas(order, OrderStatus.EXPIRED, now)) {
            throw new BusinessConflictException("Đơn hàng đã được cập nhật bởi một thao tác khác.");
        }
        applyOrderPayment(order, PaymentStatus.FAILED);
        order.setCancelledAt(now);
        orders.save(order);
        noteOrderClosed(order, "PAYMENT_TIMEOUT", now);
        auditLog.record("ORDER_STATUS_CHANGED", null, "Order", order.getId(),
                Map.of("from", OrderStatus.PENDING.name(), "to", OrderStatus.EXPIRED.name()), context);
        return true;
    }

    private PaymentReconciliationEntity applyQuery(OrderEntity order, PaymentAttemptEntity attempt,
                                                   PaymentGatewayClient.QueryResult result, RequestContext context) {
        String state = result.providerState() == null ? PaymentGatewayClient.UNKNOWN : result.providerState();
        Instant observedAt = Instant.now();
        if (order.getStatus() == OrderStatus.PENDING && order.getExpiresAt() != null
                && !order.getExpiresAt().isAfter(observedAt)
                && (order.getPaymentStatus() == null || !order.getPaymentStatus().isCaptured())) {
            expireLocked(order, observedAt, context);
        }
        boolean capturedLocally = order.getPaymentStatus() != null && order.getPaymentStatus().isCaptured();
        if (PaymentGatewayClient.CAPTURED.equals(state)) {
            if (result.amount() != null && (result.amount() != order.getTotalAmount()
                    || result.amount() != attempt.getAmount())) {
                return saveReconciliation(order, attempt, local(order), state, result.providerTransactionId(),
                        result.amount(), PaymentReconciliationReason.AMOUNT_MISMATCH,
                        PaymentReconciliationResolution.OPEN, "Số tiền cổng khác số tiền đơn.");
            }
            if (result.amount() == null || result.providerTransactionId() == null || result.providerTransactionId().isBlank()) {
                return saveReconciliation(order, attempt, local(order), state, result.providerTransactionId(),
                        result.amount(), PaymentReconciliationReason.QUERY_FAILED,
                        PaymentReconciliationResolution.OPEN, "Thiếu số tiền hoặc mã giao dịch từ cổng.");
            }
            if (!capturedLocally && order.getStatus() == OrderStatus.PENDING
                    && (order.getExpiresAt() == null || order.getExpiresAt().isAfter(Instant.now()))) {
                VerifiedPaymentCallback callback = new VerifiedPaymentCallback(attempt.getProvider(),
                        providerReference(attempt), result.providerTransactionId(), order.getOrderNumber(),
                        result.amount(), true, result.responseCode(), "00", result.message(),
                        "reconcile:" + attempt.getProvider() + ":" + result.providerTransactionId(), Instant.now());
                if (captureExists(attempt.getProvider(), result.providerTransactionId(), order)) {
                    return saveReconciliation(order, attempt, local(order), state, result.providerTransactionId(),
                            result.amount(), PaymentReconciliationReason.MATCHED,
                            PaymentReconciliationResolution.NONE, "Giao dịch đã được ghi nhận.");
                }
                if (cas(order, OrderStatus.PAID, Instant.now())) {
                    PaymentAttemptEntity target = attemptFor(order, callback);
                    capture(order, target, callback, Instant.now(), context);
                    return saveReconciliation(order, target, local(order), state, result.providerTransactionId(),
                            result.amount(), PaymentReconciliationReason.LOCAL_PENDING_PROVIDER_CAPTURED,
                            PaymentReconciliationResolution.AUTO_CAPTURED, "Đối soát đã ghi nhận thanh toán.");
                }
            }
            if (order.getStatus() == OrderStatus.EXPIRED || order.getStatus() == OrderStatus.CANCELLED) {
                VerifiedPaymentCallback callback = new VerifiedPaymentCallback(attempt.getProvider(),
                        providerReference(attempt), result.providerTransactionId(), order.getOrderNumber(),
                        result.amount(), true, result.responseCode(), "00", result.message(),
                        "reconcile-late:" + result.providerTransactionId(), Instant.now());
                if (!captureExists(attempt.getProvider(), result.providerTransactionId(), order)) {
                    lateCapture(order, attemptFor(order, callback), callback, context);
                }
                PaymentReconciliationResolution refundResolution = switch (order.getPaymentStatus()) {
                    case REFUNDED, PARTIALLY_REFUNDED -> PaymentReconciliationResolution.AUTO_REFUND_SUCCEEDED;
                    case REFUND_FAILED -> PaymentReconciliationResolution.AUTO_REFUND_FAILED;
                    default -> PaymentReconciliationResolution.OPEN;
                };
                return saveReconciliation(order, attempt, local(order), state, result.providerTransactionId(),
                        result.amount(), PaymentReconciliationReason.LOCAL_CLOSED_PROVIDER_CAPTURED,
                        refundResolution, refundResolution == PaymentReconciliationResolution.OPEN
                                ? "Đơn đã đóng; refund intent đang chờ xử lý."
                                : "Đơn đã đóng trong khi cổng đã thu tiền.");
            }
            if (capturedLocally && captureExists(attempt.getProvider(), result.providerTransactionId(), order)) {
                PaymentReconciliationReason reason = order.getPaymentStatus() == PaymentStatus.REFUNDED
                        ? PaymentReconciliationReason.REFUND_NOT_REFLECTED
                        : PaymentReconciliationReason.MATCHED;
                return saveReconciliation(order, attempt, local(order), state, result.providerTransactionId(),
                        result.amount(), reason, reason == PaymentReconciliationReason.MATCHED
                                ? PaymentReconciliationResolution.NONE : PaymentReconciliationResolution.OPEN,
                        "Giao dịch đã được ghi nhận trước đó.");
            }
            if (capturedLocally) {
                VerifiedPaymentCallback callback = new VerifiedPaymentCallback(attempt.getProvider(),
                        providerReference(attempt), result.providerTransactionId(), order.getOrderNumber(),
                        result.amount(), true, result.responseCode(), "00", result.message(),
                        "reconcile-additional:" + attempt.getId() + ":" + result.providerTransactionId(), Instant.now());
                lateCapture(order, attemptFor(order, callback), callback, context);
                return saveReconciliation(order, attempt, local(order), state, result.providerTransactionId(),
                        result.amount(), PaymentReconciliationReason.LOCAL_CLOSED_PROVIDER_CAPTURED,
                        PaymentReconciliationResolution.OPEN, "Giao dịch thu thêm đã được ghi nhận; đã tạo yêu cầu hoàn tiền.");
            }
            return saveReconciliation(order, attempt, local(order), state, result.providerTransactionId(),
                    result.amount(), PaymentReconciliationReason.MATCHED, PaymentReconciliationResolution.NONE,
                    "Khớp trạng thái đã thu.");
        }
        if (PaymentGatewayClient.FAILED.equals(state) || PaymentGatewayClient.NOT_FOUND.equals(state)) {
            if (capturedLocally) {
                return saveReconciliation(order, attempt, local(order), state, result.providerTransactionId(),
                        result.amount(), PaymentReconciliationReason.LOCAL_CAPTURED_PROVIDER_FAILED,
                        PaymentReconciliationResolution.OPEN, "Đơn đã thu cục bộ nhưng cổng không xác nhận.");
            }
            if (attempt.getStatus() != null && attempt.getStatus().isOpen()) {
                attempt.setStatus(PaymentAttemptStatus.FAILED);
                attempt.setFailureCode(limit(result.responseCode(), 40));
                attempt.setFailureReason(limit(result.message(), 500));
                attempt.setUpdatedAt(Instant.now());
                attempts.save(attempt);
            }
            return saveReconciliation(order, attempt, local(order), state, result.providerTransactionId(),
                    result.amount(), PaymentReconciliationReason.MATCHED, PaymentReconciliationResolution.NONE,
                    "Cả hai bên đều chưa thu tiền.");
        }
        if (PaymentGatewayClient.REFUND_PENDING.equals(state)) {
            PaymentReconciliationReason reason = order.getPaymentStatus() == PaymentStatus.REFUNDED
                    ? PaymentReconciliationReason.MATCHED : PaymentReconciliationReason.REFUND_NOT_REFLECTED;
            return saveReconciliation(order, attempt, local(order), state, result.providerTransactionId(),
                    result.amount(), reason, reason == PaymentReconciliationReason.MATCHED
                            ? PaymentReconciliationResolution.NONE : PaymentReconciliationResolution.OPEN,
                    "Cổng đang xử lý hoàn tiền.");
        }
        PaymentReconciliationReason reason = PaymentGatewayClient.PENDING.equals(state)
                ? PaymentReconciliationReason.PROVIDER_PENDING : PaymentReconciliationReason.QUERY_FAILED;
        return saveReconciliation(order, attempt, local(order), state, result.providerTransactionId(),
                result.amount(), reason, PaymentReconciliationResolution.NONE,
                result.message() == null ? "Chưa đối soát được." : result.message());
    }

    private void saveTransaction(OrderEntity order, PaymentAttemptEntity attempt, PaymentEntity payment,
                                 String provider, String providerTransactionId, ProviderTransactionType type,
                                 long amount, ProviderTransactionStatus status, String key, String responseCode,
                                 String message, String raw, Instant now) {
        transactions.save(transaction(order, attempt, payment, provider, providerTransactionId, type, amount,
                status, key, responseCode, message, raw, now));
    }

    private PaymentProviderTransactionEntity transaction(OrderEntity order, PaymentAttemptEntity attempt,
                                                         PaymentEntity payment, String provider,
                                                         String providerTransactionId, ProviderTransactionType type,
                                                         long amount, ProviderTransactionStatus status, String key,
                                                         String responseCode, String message, String raw, Instant now) {
        PaymentProviderTransactionEntity row = new PaymentProviderTransactionEntity();
        row.setOrderId(order.getId());
        row.setAttemptId(attempt == null ? null : attempt.getId());
        row.setPaymentId(payment == null ? null : payment.getId());
        row.setProvider(provider);
        row.setProviderTransactionId(blankToNull(providerTransactionId));
        row.setProviderRequestId(key);
        row.setTransactionType(type);
        row.setAmount(amount);
        row.setCurrency(currency(order));
        row.setStatus(status);
        row.setIdempotencyKey(key);
        row.setResponseCode(limit(responseCode, 40));
        row.setResponseMessage(limit(message, 500));
        row.setRawResponse(limit(raw, PAYLOAD_LIMIT));
        row.setCreatedAt(now);
        row.setProcessedAt(now);
        return row;
    }

    private PaymentReconciliationEntity saveReconciliation(OrderEntity order, PaymentAttemptEntity attempt,
                                                           String localStatus, String providerStatus,
                                                           String providerTransactionId, Long providerAmount,
                                                           PaymentReconciliationReason reason,
                                                           PaymentReconciliationResolution resolution, String detail) {
        PaymentReconciliationEntity row = new PaymentReconciliationEntity();
        row.setOrderId(order.getId());
        row.setAttemptId(attempt == null ? null : attempt.getId());
        row.setProvider(attempt == null || attempt.getProvider() == null ? "UNKNOWN" : attempt.getProvider());
        row.setMerchantReference(attempt == null || attempt.getMerchantReference() == null
                ? order.getOrderNumber() : attempt.getMerchantReference());
        row.setLocalStatus(limit(localStatus, 30));
        row.setProviderStatus(limit(providerStatus, 30));
        row.setProviderTransactionId(limit(providerTransactionId, 190));
        row.setProviderAmount(providerAmount);
        row.setReason(reason);
        row.setResolution(resolution);
        row.setDetail(limit(detail, 500));
        row.setCheckedAt(Instant.now());
        return reconciliations.save(row);
    }

    private boolean cas(OrderEntity order, OrderStatus target, Instant now) {
        OrderStatus current = order.getStatus();
        if (current == target) return true;
        if (current == null || !current.canTransitionTo(target)) return false;
        int updated = orders.compareAndSetStatus(order.getId(), current.name(), target.name(), now);
        if (updated != 1) return false;
        order.transitionTo(target);
        if (target == OrderStatus.CANCELLED || target == OrderStatus.EXPIRED) order.setCancelledAt(now);
        return true;
    }

    private void applyOrderPayment(OrderEntity order, PaymentStatus target) {
        PaymentStatus current = order.getPaymentStatus() == null ? PaymentStatus.PENDING : order.getPaymentStatus();
        if (current == target) return;
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateException("Invalid order payment transition: " + current + " -> " + target);
        }
        order.setPaymentStatus(target);
    }

    private void failOpenPayments(OrderEntity order, String reason, Instant now) {
        for (PaymentEntity payment : payments.findByOrderIdForUpdateOrderByIdAsc(order.getId())) {
            if (payment.getStatus() != PaymentStatus.PENDING && payment.getStatus() != PaymentStatus.PROCESSING) continue;
            payment.transitionTo(PaymentStatus.FAILED);
            payment.setFailureReason(limit(reason, 500));
            payment.setProcessedAt(now);
            payments.save(payment);
        }
    }

    private void failOpenAttempts(OrderEntity order, PaymentAttemptStatus status, String reason, Instant now) {
        for (PaymentAttemptEntity attempt : attempts.findByOrderIdOrderByCreatedAtDesc(order.getId())) {
            if (attempt.getStatus() == null || !attempt.getStatus().isOpen()) continue;
            attempt.setStatus(status);
            attempt.setFailureCode(limit(reason, 40));
            attempt.setFailureReason(limit(reason, 500));
            attempt.setUpdatedAt(now);
            attempts.save(attempt);
        }
    }

    private void restoreStock(java.util.UUID orderId) {
        if (orderItems == null || products == null) return;
        for (OrderItemEntity item : orderItems.findByOrderIdOrderByIdAsc(orderId).stream()
                .sorted(Comparator.comparing(OrderItemEntity::getProductId)).toList()) {
            ProductEntity product = products.findByIdForUpdateIncludingInactive(item.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm để hoàn tồn kho."));
            product.setStockQuantity(Math.addExact(product.getStockQuantity(), item.getQuantity()));
            products.save(product);
        }
    }

    private PaymentAttemptEntity attemptFor(OrderEntity order, VerifiedPaymentCallback callback) {
        if ("VNPAY".equalsIgnoreCase(callback.provider()) && callback.orderNumber() != null) {
            PaymentAttemptEntity byReference = attempts.findByProviderAndProviderTxnRef(
                    callback.provider(), callback.orderNumber()).orElse(null);
            if (byReference != null && order.getId().equals(byReference.getOrderId())) return byReference;
            if (callback.orderNumber().equals(order.getOrderNumber())) {
                for (PaymentAttemptEntity historical : attempts.findByOrderIdOrderByCreatedAtDesc(order.getId())) {
                    if ("VNPAY".equalsIgnoreCase(historical.getProvider())
                            && blankToNull(historical.getProviderTxnRef()) == null
                            && order.getOrderNumber().equals(historical.getMerchantReference())) return historical;
                }
            }
        }
        if (callback.providerTransactionId() != null) {
            Optional<PaymentAttemptEntity> byTxn = attempts.findByProviderAndProviderTransactionId(
                    callback.provider(), callback.providerTransactionId());
            if (byTxn.isPresent() && order.getId().equals(byTxn.get().getOrderId())) return byTxn.get();
        }
        PaymentAttemptEntity open = openAttempt(order, callback.provider());
        if (open != null) return open;
        PaymentAttemptEntity latest = latestAttempt(order, callback.provider());
        if (latest != null && latest.getStatus() != PaymentAttemptStatus.SUCCEEDED) return latest;
        PaymentAttemptEntity created = new PaymentAttemptEntity();
        created.setOrderId(order.getId());
        created.setProvider(callback.provider());
        created.setMerchantReference(order.getOrderNumber());
        created.setAmount(order.getTotalAmount());
        created.setCurrency(currency(order));
        created.setStatus(PaymentAttemptStatus.PROCESSING);
        created.setCreatedAt(receivedAt(callback));
        created.setUpdatedAt(receivedAt(callback));
        return attempts.save(created);
    }

    private PaymentAttemptEntity openAttempt(OrderEntity order, String provider) {
        for (PaymentAttemptEntity row : attempts.findByOrderIdOrderByCreatedAtDesc(order.getId())) {
            if (provider.equalsIgnoreCase(row.getProvider()) && row.getStatus() != null && row.getStatus().isOpen()) {
                return row;
            }
        }
        return null;
    }

    private PaymentAttemptEntity latestAttempt(OrderEntity order, String provider) {
        for (PaymentAttemptEntity row : attempts.findByOrderIdOrderByCreatedAtDesc(order.getId())) {
            if (provider.equalsIgnoreCase(row.getProvider())) return row;
        }
        return null;
    }

    private PaymentAttemptEntity latestOnlineAttempt(OrderEntity order) {
        for (PaymentAttemptEntity row : attempts.findByOrderIdOrderByCreatedAtDesc(order.getId())) {
            if ("VNPAY".equalsIgnoreCase(row.getProvider()) || "MOMO".equalsIgnoreCase(row.getProvider())) return row;
        }
        return null;
    }

    private PaymentEntity editablePayment(OrderEntity order, String provider, PaymentAttemptEntity attempt) {
        if (attempt != null && attempt.getId() != null) {
            List<PaymentEntity> linked = payments.findByOrderIdAndProviderAndAttemptIdOrderByCreatedAtAsc(
                    order.getId(), provider, attempt.getId());
            if (!linked.isEmpty()) {
                PaymentEntity fallback = null;
                for (PaymentEntity row : linked) {
                    if (row.getStatus() == PaymentStatus.PENDING || row.getStatus() == PaymentStatus.PROCESSING
                            || row.getStatus() == PaymentStatus.FAILED) return row;
                    fallback = row;
                }
                return fallback;
            }
        }
        PaymentEntity fallback = null;
        for (PaymentEntity row : payments.findByOrderIdForUpdateOrderByIdAsc(order.getId())) {
            if (row.getProvider() == null || !row.getProvider().equalsIgnoreCase(provider)) continue;
            if (row.getStatus() == PaymentStatus.PENDING || row.getStatus() == PaymentStatus.PROCESSING
                    || row.getStatus() == PaymentStatus.FAILED) return row;
            fallback = row;
        }
        return fallback;
    }

    private PaymentAttemptEntity attemptForPayment(OrderEntity order, PaymentEntity payment, String provider) {
        if (payment.getAttemptId() != null) {
            PaymentAttemptEntity linked = attempts.findById(payment.getAttemptId()).orElse(null);
            if (linked != null && order.getId().equals(linked.getOrderId())) return linked;
        }
        if (payment.getProviderTransactionId() != null) {
            PaymentAttemptEntity byTransaction = attempts.findByProviderAndProviderTransactionId(
                    provider, payment.getProviderTransactionId()).orElse(null);
            if (byTransaction != null && order.getId().equals(byTransaction.getOrderId())) return byTransaction;
        }
        return latestAttempt(order, provider);
    }

    private PaymentEntity capturePayment(OrderEntity order, String provider) {
        return payments.findByOrderIdForUpdateOrderByIdAsc(order.getId()).stream()
                .filter(row -> row.getProvider() != null && row.getProvider().equalsIgnoreCase(provider))
                .filter(row -> row.getStatus() == PaymentStatus.SUCCEEDED
                        || row.getStatus() == PaymentStatus.REFUND_PENDING
                        || row.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                        || row.getStatus() == PaymentStatus.REFUND_FAILED)
                .max(Comparator.comparing((PaymentEntity row) -> row.getCreatedAt() == null
                                ? Instant.EPOCH : row.getCreatedAt())
                        .thenComparing(row -> row.getId() == null ? "" : row.getId().toString()))
                .orElse(null);
    }

    private boolean captureExists(String provider, String providerTransactionId, OrderEntity order) {
        return transactions.findByProviderAndProviderTransactionIdAndTransactionType(
                        provider, providerTransactionId, ProviderTransactionType.CAPTURE)
                .filter(row -> row.getStatus() == ProviderTransactionStatus.SUCCEEDED
                        && order.getId().equals(row.getOrderId()))
                .isPresent();
    }

    private boolean ownedByAnotherOrder(OrderEntity order, VerifiedPaymentCallback callback) {
        Optional<PaymentEntity> payment = payments.findByProviderTransactionId(callback.providerTransactionId());
        if (payment.isPresent() && !order.getId().equals(payment.get().getOrderId())) return true;
        return transactions.findByProviderAndProviderTransactionIdAndTransactionType(
                        callback.provider(), callback.providerTransactionId(), ProviderTransactionType.CAPTURE)
                .filter(row -> !order.getId().equals(row.getOrderId()))
                .isPresent();
    }

    private PaymentGatewayClient gateway(String provider) {
        for (PaymentGatewayClient client : gateways) {
            if (client.providerCode().equalsIgnoreCase(provider)) return client;
        }
        return null;
    }

    private String createDate(OrderEntity order, PaymentEntity payment, PaymentAttemptEntity attempt) {
        if (attempt != null && attempt.getProviderCreateDate() != null && !attempt.getProviderCreateDate().isBlank()) {
            return attempt.getProviderCreateDate();
        }
        Instant instant = payment.getProcessedAt() != null ? payment.getProcessedAt()
                : order.getPaidAt() != null ? order.getPaidAt() : payment.getCreatedAt();
        return instant == null ? null : VN_DATE.format(instant);
    }

    private void insertIsolated(Object row) {
        Runnable save = () -> {
            if (row instanceof PaymentCallbackEntity callback) callbacks.saveAndFlush(callback);
            else if (row instanceof PaymentProviderTransactionEntity transaction) transactions.saveAndFlush(transaction);
        };
        try {
            if (independent == null) save.run();
            else independent.executeWithoutResult(status -> save.run());
        } catch (RuntimeException exception) {
            if (!isIntegrityViolation(exception)) throw exception;
        }
    }

    private static boolean isIntegrityViolation(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static PaymentCallbackResult replay(PaymentCallbackEntity existing) {
        return switch (existing.getOutcome()) {
            case APPLIED, DUPLICATE -> new PaymentCallbackResult("00", "Order already confirmed", PaymentCallbackOutcome.DUPLICATE);
            case PAYMENT_FAILED, IGNORED -> new PaymentCallbackResult("00", "Transaction was not successful", PaymentCallbackOutcome.DUPLICATE);
            case LATE_CAPTURE -> new PaymentCallbackResult("00", "Late capture acknowledged", PaymentCallbackOutcome.DUPLICATE);
            case REJECTED_SIGNATURE -> new PaymentCallbackResult("97", "Invalid signature", PaymentCallbackOutcome.REJECTED_SIGNATURE);
            case AMOUNT_MISMATCH -> new PaymentCallbackResult("04", "Invalid amount", PaymentCallbackOutcome.AMOUNT_MISMATCH);
            case ORDER_NOT_FOUND -> new PaymentCallbackResult("01", "Order not found", PaymentCallbackOutcome.ORDER_NOT_FOUND);
            case CONFLICT -> new PaymentCallbackResult("02", "Order already confirmed", PaymentCallbackOutcome.CONFLICT);
        };
    }

    private static Decision decided(String code, String message, PaymentCallbackOutcome outcome, java.util.UUID attemptId) {
        return new Decision(new PaymentCallbackResult(code, message, outcome), outcome, attemptId);
    }

    private static PaymentCallbackEntity callbackRow(String provider, java.util.UUID orderId, java.util.UUID attemptId,
                                                     String providerTransactionId, boolean signatureValid,
                                                     PaymentCallbackOutcome outcome, String responseCode,
                                                     String transactionStatus, Long amount, String hash, String payload,
                                                     Instant receivedAt) {
        PaymentCallbackEntity row = new PaymentCallbackEntity();
        row.setProvider(provider);
        row.setOrderId(orderId);
        row.setAttemptId(attemptId);
        row.setProviderTransactionId(limit(providerTransactionId, 190));
        row.setSignatureValid(signatureValid);
        row.setOutcome(outcome);
        row.setResponseCode(limit(responseCode, 40));
        row.setTransactionStatus(limit(transactionStatus, 40));
        row.setAmount(amount);
        row.setPayloadSha256(hash);
        row.setRawPayload(limit(payload == null ? "" : payload, PAYLOAD_LIMIT));
        row.setReceivedAt(receivedAt);
        return row;
    }

    private static String hash(String provider, String payload) {
        return PaymentPayloads.sha256((provider == null ? "" : provider) + "\n" + (payload == null ? "" : payload));
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) throw new BusinessConflictException("Mã hoàn tiền là bắt buộc.");
        String trimmed = key.trim();
        if (trimmed.length() > 190) throw new BusinessConflictException("Mã hoàn tiền quá dài.");
        return trimmed;
    }

    private static String lateKey(String provider, String transactionId) {
        String key = "late:" + provider + ":" + transactionId;
        return key.length() <= 190 ? key : "late:" + PaymentPayloads.sha256(key);
    }

    private static java.util.UUID returnResolutionId(String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.startsWith("return-")) return null;
        String key = idempotencyKey.substring("return-".length());
        if (key.length() < 36) return null;
        try { return java.util.UUID.fromString(key.substring(0, 36)); }
        catch (IllegalArgumentException exception) { return null; }
    }

    private static Instant receivedAt(VerifiedPaymentCallback callback) {
        return callback.receivedAt() == null ? Instant.now() : callback.receivedAt();
    }

    private static String local(OrderEntity order) {
        return order.getStatus().name() + "/" + order.getPaymentStatus();
    }

    private static String currency(OrderEntity order) {
        return order.getCurrency() == null || order.getCurrency().isBlank() ? "VND" : order.getCurrency();
    }

    private static String ip(RequestContext context) {
        if (context == null || context.ipAddress() == null || context.ipAddress().isBlank()) return "127.0.0.1";
        return context.ipAddress();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record Decision(PaymentCallbackResult result, PaymentCallbackOutcome outcome, java.util.UUID attemptId) {
    }

    private record RefundBooking(boolean succeeded, RefundApplication application, String message) {
    }

    private record RefundPlan(java.util.UUID orderId, String key, String provider,
                              PaymentGatewayClient.RefundCommand command, long amount,
                              java.util.UUID actorId, RequestContext context, boolean lateClose,
                              PaymentGatewayClient gateway) {
    }

    private record RefundPreparation(RefundPlan plan, RefundApplication application) {
        static RefundPreparation plan(RefundPlan plan) { return new RefundPreparation(plan, null); }
        static RefundPreparation replay() { return new RefundPreparation(null, RefundApplication.REPLAY); }
        static RefundPreparation none() { return new RefundPreparation(null, null); }
    }

    private record ReconcilePreparation(PaymentReconciliationEntity immediate, java.util.UUID attemptId,
                                        PaymentGatewayClient gateway,
                                        PaymentGatewayClient.QueryCommand command) {
        static ReconcilePreparation immediate(PaymentReconciliationEntity result) {
            return new ReconcilePreparation(result, null, null, null);
        }
        static ReconcilePreparation query(java.util.UUID attemptId, PaymentGatewayClient gateway,
                                          PaymentGatewayClient.QueryCommand command) {
            return new ReconcilePreparation(null, attemptId, gateway, command);
        }
    }
}
