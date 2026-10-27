package com.utewedding.app.integration;

import com.utewedding.app.config.VNPayConfig;
import com.utewedding.app.observability.CommerceMetrics;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.PaymentEntity;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.PaymentRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.FulfillmentService;
import com.utewedding.app.service.payment.PaymentCallbackResult;
import com.utewedding.app.service.payment.PaymentIntegrityService;
import com.utewedding.app.service.payment.GroupedPaymentIntegrityService;
import com.utewedding.app.service.payment.PaymentPayloads;
import com.utewedding.app.service.payment.VerifiedPaymentCallback;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.ZoneId;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * VNPay sandbox adapter. The amount is always read from the persisted order;
 * it is never trusted from the browser request. IPN is signature-checked,
 * amount-checked, and idempotently updates the order to PAID after a
 * successful payment.
 */
@Service
public class VNPayService {

    private static final Logger log = LoggerFactory.getLogger(VNPayService.class);
    private static final DateTimeFormatter VN_DATE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final VNPayConfig config;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final AuditLogPort auditLog;
    private final FulfillmentService fulfillmentService;
    private CommerceMetrics metrics = CommerceMetrics.noop();
    private PaymentIntegrityService paymentIntegrity;

    @Autowired
    public VNPayService(
            VNPayConfig config,
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            AuditLogPort auditLog,
            FulfillmentService fulfillmentService) {
        this.config = Objects.requireNonNull(config, "config");
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository");
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.fulfillmentService = Objects.requireNonNull(fulfillmentService, "fulfillmentService");
    }

    @Autowired(required = false)
    void attachMetrics(CommerceMetrics metrics) {
        this.metrics = metrics == null ? CommerceMetrics.noop() : metrics;
    }

    @Autowired(required = false)
    void attachPaymentIntegrity(PaymentIntegrityService paymentIntegrity) {
        this.paymentIntegrity = paymentIntegrity;
    }

    /** Builds the exact redirect URL used by the browser for VNPay Sandbox. */
    public PaymentUrl createPaymentUrl(UUID userId, String orderNumber, String orderInfo, String clientIp) {
        try {
            return createPaymentUrlBody(userId, orderNumber, orderInfo, clientIp);
        } catch (ResponseStatusException ex) {
            metrics.payment("VNPAY", ex.getStatusCode().is5xxServerError() ? "error" : "rejected");
            throw ex;
        } catch (RuntimeException ex) {
            metrics.payment("VNPAY", "error");
            log.error("VNPay payment URL creation failed");
            throw ex;
        }
    }

    private PaymentUrl createPaymentUrlBody(UUID userId, String orderNumber, String orderInfo, String clientIp) {
        requireConfiguration();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Yêu cầu đăng nhập.");
        }
        String normalizedOrderNumber = requireText(orderNumber, "orderNumber");
        OrderEntity order = orderRepository.findByOrderNumberAndUserId(normalizedOrderNumber, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng."));
        if (order.getCheckoutGroupId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Đơn hàng thuộc nhóm checkout. Hãy tạo phiên thanh toán qua nhóm checkout.");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Đơn hàng không còn ở trạng thái chờ thanh toán.");
        }
        if (order.getTotalAmount() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Số tiền đơn hàng phải lớn hơn 0.");
        }

        if (paymentIntegrity == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Dịch vụ tạo phiên thanh toán chưa sẵn sàng.");
        }
        PaymentIntegrityService.VnpaySession session = paymentIntegrity.createOrReuseVnpaySession(
                order.getId(), hasText(orderInfo) ? orderInfo : "Thanh toan don " + normalizedOrderNumber,
                normalizeIp(clientIp), config.getExpireMinutes(),
                new RequestContext(clientIp, null, null));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Version", config.getVersion());
        params.put("vnp_Command", config.getCommand());
        params.put("vnp_TmnCode", config.getTmnCode());
        params.put("vnp_Amount", amountInSmallestUnit(session.amount()));
        params.put("vnp_CurrCode", config.getCurrency());
        params.put("vnp_TxnRef", session.providerReference());
        params.put("vnp_OrderInfo", session.orderInfo());
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", config.getLocale());
        params.put("vnp_ReturnUrl", config.getReturnUrl());
        params.put("vnp_IpAddr", session.clientIp());
        params.put("vnp_CreateDate", session.providerCreateDate());
        params.put("vnp_ExpireDate", VN_DATE.format(session.expiresAt().atZone(VN_ZONE)));

        String query = canonicalQueryString(params);
        String signature = generateHmacSha512(query);
        String paymentUrl = config.getPayUrl() + "?" + query
                + "&vnp_SecureHashType=HmacSHA512"
                + "&vnp_SecureHash=" + signature;
        metrics.payment("VNPAY", "started");
        return new PaymentUrl(normalizedOrderNumber, session.amount(), paymentUrl);
    }

    public boolean paymentWindowOpen(Instant orderExpiry) {
        return orderExpiry != null && orderExpiry.isAfter(Instant.now());
    }

    /** Handles VNPay's server-to-server IPN callback. */
    @Transactional
    public IpnResponse handleIpn(Map<String, String> callbackParameters) {
        return handleIpn(callbackParameters, null);
    }

    @Transactional
    public IpnResponse handleIpn(Map<String, String> callbackParameters, RequestContext context) {
        try {
            IpnResponse response = handleIpnBody(callbackParameters, context);
            String outcome = vnpayOutcome(response);
            metrics.payment("VNPAY", outcome);
            if ("rejected".equals(outcome)) log.error("VNPay IPN rejected code={}", response.rspCode());
            return response;
        } catch (RuntimeException ex) {
            metrics.payment("VNPAY", "error");
            log.error("VNPay IPN failed");
            throw ex;
        }
    }

    private IpnResponse handleIpnBody(Map<String, String> callbackParameters, RequestContext context) {
        if (paymentIntegrity != null) {
            return delegateIpn(callbackParameters, context);
        }
        if (!verifySignature(callbackParameters)) {
            return new IpnResponse("97", "Invalid signature");
        }

        String orderNumber = callbackParameters.get("vnp_TxnRef");
        if (!hasText(orderNumber)) {
            return new IpnResponse("01", "Order not found");
        }

        OrderEntity order = orderRepository.findByOrderNumberForUpdate(orderNumber)
                .orElse(null);
        if (order == null) {
            return new IpnResponse("01", "Order not found");
        }

        long amount;
        try {
            amount = Long.parseLong(Objects.requireNonNull(
                    callbackParameters.get("vnp_Amount"), "vnp_Amount"));
        } catch (RuntimeException exception) {
            return new IpnResponse("04", "Invalid amount");
        }
        long expectedAmount;
        try {
            expectedAmount = Math.multiplyExact(order.getTotalAmount(), 100L);
        } catch (ArithmeticException exception) {
            return new IpnResponse("04", "Invalid amount");
        }
        if (amount != expectedAmount) {
            return new IpnResponse("04", "Invalid amount");
        }

        // A failed/cancelled VNPay transaction is acknowledged but cannot mark PAID.
        if (!"00".equals(callbackParameters.get("vnp_ResponseCode"))
                || !"00".equals(callbackParameters.get("vnp_TransactionStatus"))) {
            return new IpnResponse("00", "Transaction was not successful");
        }

        String transactionId = callbackParameters.get("vnp_TransactionNo");
        if (!hasText(transactionId)) {
            return new IpnResponse("04", "Invalid transaction");
        }
        var previousPayment = paymentRepository.findByProviderTransactionId(transactionId);
        if (previousPayment.isPresent() && !order.getId().equals(previousPayment.get().getOrderId())) {
            return new IpnResponse("04", "Transaction already belongs to another order");
        }

        // VNPay can retry an IPN. Returning 00 makes this branch idempotent.
        if (order.getStatus() == OrderStatus.PAID
                || order.getPaymentStatus() == PaymentStatus.SUCCEEDED) {
            return new IpnResponse("00", "Order already confirmed");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            return new IpnResponse("02", "Order already confirmed");
        }

        InstantHolder now = new InstantHolder();
        OrderStatus previousOrderStatus = order.getStatus();
        PaymentStatus previousPaymentStatus = order.getPaymentStatus();
        order.transitionTo(OrderStatus.PAID);
        order.setPaymentStatus(PaymentStatus.SUCCEEDED);
        order.setPaidAt(now.value());
        orderRepository.save(order);
        auditLog.record("ORDER_STATUS_CHANGED", null, "Order", order.getId(),
                Map.of("from", previousOrderStatus.name(), "to", order.getStatus().name(),
                        "provider", "VNPAY"), context);

        PaymentEntity payment = previousPayment.orElseGet(() -> paymentRepository
                .findByOrderIdOrderByCreatedAtDesc(order.getId()).stream()
                .filter(row -> "VNPAY".equalsIgnoreCase(row.getProvider())
                        && row.getStatus() == PaymentStatus.PENDING)
                .findFirst()
                .orElseGet(PaymentEntity::new));
        payment.setOrderId(order.getId());
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setAmount(order.getTotalAmount());
        payment.setCurrency(order.getCurrency());
        payment.setProvider("VNPAY");
        payment.setProviderTransactionId(transactionId);
        payment.setProcessedAt(now.value());
        paymentRepository.save(payment);
        auditLog.record("PAYMENT_STATUS_CHANGED", null, "Payment", payment.getId(),
                Map.of("status", String.valueOf(previousPaymentStatus) + " -> " + payment.getStatus().name(),
                        "provider", "VNPAY"), context);
        fulfillmentService.ensureForOrder(order.getId(), context);
        return new IpnResponse("00", "Payment Success");
    }

    private IpnResponse delegateIpn(Map<String, String> parameters, RequestContext context) {
        if (!verifySignature(parameters)) {
            paymentIntegrity.recordInvalidSignature("VNPAY", PaymentPayloads.canonical(parameters),
                    parameters == null ? null : parameters.get("vnp_TxnRef"),
                    parameters == null ? null : parameters.get("vnp_ResponseCode"));
            return new IpnResponse("97", "Invalid signature");
        }
        String reference = parameters == null ? null : parameters.get("vnp_TxnRef");
        if (paymentIntegrity != null && paymentIntegrity.hasGroupedVnpayReference(reference)
                && !Objects.equals(config.getTmnCode(), parameters.get("vnp_TmnCode"))) {
            paymentIntegrity.recordGroupVnpayRejection(reference, PaymentPayloads.canonical(parameters),
                    "97", "Merchant code mismatch");
            return new IpnResponse("97", "Invalid merchant");
        }
        PaymentCallbackResult result = paymentIntegrity.applyVerified(vnpayCallback(parameters), context);
        return new IpnResponse(result.code(), vnpayMessage(result));
    }

    /** Creates or reuses one persisted VNPAY session for the checkout-group total. */
    public GroupPaymentSessionResult createGroupPaymentSession(UUID userId, UUID groupId, String clientIp,
                                                                RequestContext context) {
        requireConfiguration();
        if (paymentIntegrity == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Dịch vụ tạo phiên thanh toán chưa sẵn sàng.");
        }
        GroupedPaymentIntegrityService.SessionResult result = paymentIntegrity.createOrReuseVnpayGroupSession(
                userId, groupId, "Thanh toan nhom checkout", normalizeIp(clientIp),
                config.getExpireMinutes(), context);
        if (result.status() != GroupedPaymentIntegrityService.SessionState.READY || result.session() == null) {
            return new GroupPaymentSessionResult(result.status().name(), result.groupId(), result.groupNumber(),
                    result.totalAmount(), result.currency(), result.expiresAt(), null, result.message());
        }
        GroupedPaymentIntegrityService.VnpayGroupSession session = result.session();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Version", config.getVersion());
        params.put("vnp_Command", config.getCommand());
        params.put("vnp_TmnCode", config.getTmnCode());
        params.put("vnp_Amount", amountInSmallestUnit(session.amount()));
        params.put("vnp_CurrCode", config.getCurrency());
        params.put("vnp_TxnRef", session.providerReference());
        params.put("vnp_OrderInfo", session.orderInfo());
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", config.getLocale());
        params.put("vnp_ReturnUrl", config.getReturnUrl());
        params.put("vnp_IpAddr", session.clientIp());
        params.put("vnp_CreateDate", session.providerCreateDate());
        params.put("vnp_ExpireDate", VN_DATE.format(session.expiresAt().atZone(VN_ZONE)));
        String query = canonicalQueryString(params);
        String url = config.getPayUrl() + "?" + query + "&vnp_SecureHashType=HmacSHA512"
                + "&vnp_SecureHash=" + generateHmacSha512(query);
        metrics.payment("VNPAY", "started");
        return new GroupPaymentSessionResult("READY", result.groupId(), result.groupNumber(),
                session.amount(), session.currency(), session.expiresAt(), url, null);
    }

    private static VerifiedPaymentCallback vnpayCallback(Map<String, String> parameters) {
        String response = parameters.get("vnp_ResponseCode");
        String txnStatus = parameters.get("vnp_TransactionStatus");
        boolean success = "00".equals(response) && "00".equals(txnStatus);
        Long amount = null;
        String rawAmount = parameters.get("vnp_Amount");
        if (rawAmount != null) {
            try {
                long minor = Long.parseLong(rawAmount);
                if (minor % 100L == 0) amount = minor / 100L;
            } catch (NumberFormatException ignored) {
                amount = null;
            }
        }
        String transactionId = parameters.get("vnp_TransactionNo");
        if (transactionId != null && (transactionId.isBlank() || "0".equals(transactionId))) transactionId = null;
        return new VerifiedPaymentCallback("VNPAY", parameters.get("vnp_TxnRef"), transactionId, null, amount,
                success, response, txnStatus, parameters.get("vnp_Message"),
                PaymentPayloads.canonical(parameters), java.time.Instant.now());
    }

    private static String vnpayMessage(PaymentCallbackResult result) {
        return switch (result.outcome()) {
            case APPLIED -> "Payment Success";
            case DUPLICATE -> "Order already confirmed";
            case PAYMENT_FAILED, IGNORED -> "Transaction was not successful";
            case LATE_CAPTURE -> "Late capture acknowledged";
            case REJECTED_SIGNATURE -> "Invalid signature";
            case AMOUNT_MISMATCH -> "Invalid amount";
            case ORDER_NOT_FOUND -> "Order not found";
            case CONFLICT -> "Order already confirmed";
        };
    }

    private static String vnpayOutcome(IpnResponse response) {
        if ("00".equals(response.rspCode()) && "Payment Success".equals(response.message())) return "succeeded";
        if ("00".equals(response.rspCode()) && "Order already confirmed".equals(response.message())) return "replay";
        if ("00".equals(response.rspCode()) && response.message() != null && response.message().startsWith("Late")) {
            return "late";
        }
        if ("00".equals(response.rspCode())) return "declined";
        if ("97".equals(response.rspCode()) || "04".equals(response.rspCode())) return "rejected";
        return "failed";
    }

    /** Verifies a browser return without treating it as the source of truth. */
    public ReturnResult inspectReturn(Map<String, String> parameters) {
        boolean valid = verifySignature(parameters);
        String reference = valid ? parameters.get("vnp_TxnRef") : null;
        if (valid && paymentIntegrity != null && paymentIntegrity.hasGroupedVnpayReference(reference)
                && !Objects.equals(config.getTmnCode(), parameters.get("vnp_TmnCode"))) {
            valid = false;
            reference = null;
        }
        String orderNumber = reference;
        UUID groupId = null;
        String groupNumber = null;
        String groupPaymentStatus = null;
        String status = "UNKNOWN";
        boolean savedSession = false;
        boolean orderPaid = false;
        com.utewedding.app.domain.PaymentAttemptStatus attemptStatus = null;
        if (valid && hasText(reference)) {
            if (paymentIntegrity != null) {
                PaymentIntegrityService.VnpayAttemptReference resolved =
                        paymentIntegrity.resolveVnpayReference(reference);
                if (resolved != null) {
                    savedSession = true;
                    if (resolved.groupOwned()) {
                        groupId = resolved.checkoutGroupId();
                        groupNumber = resolved.groupNumber();
                        groupPaymentStatus = resolved.groupPaymentStatus() == null
                                ? "UNKNOWN" : resolved.groupPaymentStatus().name();
                        orderNumber = null;
                        status = groupPaymentStatus;
                        orderPaid = "PAID".equals(groupPaymentStatus)
                                || "PARTIALLY_REFUNDED".equals(groupPaymentStatus)
                                || "REFUNDED".equals(groupPaymentStatus);
                    } else {
                        orderNumber = resolved.orderNumber();
                    }
                    attemptStatus = resolved.status();
                }
            }
            if (savedSession && groupId != null) {
                // The group status above is canonical; the browser return cannot promote it.
            } else if (savedSession || paymentIntegrity == null) {
                OrderEntity order = orderRepository.findByOrderNumber(orderNumber).orElse(null);
                status = order == null ? "NOT_FOUND" : order.getStatus().name();
                orderPaid = order != null && (order.getStatus() == OrderStatus.PAID
                        || order.getPaymentStatus() != null && order.getPaymentStatus().isCaptured());
            } else {
                orderNumber = null;
                status = "UNKNOWN";
            }
        }
        String responseCode = parameters.getOrDefault("vnp_ResponseCode", "99");
        return new ReturnResult(
                valid,
                orderNumber,
                responseCode,
                valid ? "Đã nhận kết quả từ VNPAY." : "Không thể xác thực kết quả thanh toán.",
                status,
                orderPaid,
                savedSession,
                savedSession ? reference : null,
                attemptStatus,
                groupId,
                groupNumber,
                groupPaymentStatus);
    }

    /** Exposed for focused unit tests and operational signature diagnostics. */
    public boolean verifySignature(Map<String, String> parameters) {
        if (parameters == null || !hasText(parameters.get("vnp_SecureHash"))) {
            return false;
        }
        String expected = generateHmacSha512(canonicalQueryString(parameters));
        return constantTimeEquals(expected, parameters.get("vnp_SecureHash"));
    }

    /** HMAC-SHA512, encoded as the lowercase hex VNPay expects. */
    public String generateHmacSha512(String data) {
        requireConfiguration();
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(
                    config.getHashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return String.format(Locale.ROOT, "%0128x", new BigInteger(1,
                    mac.doFinal(data.getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Không thể tạo chữ ký HMAC-SHA512.", exception);
        }
    }

    /** VNPay canonical query: sorted keys, URL encoded values, no secure hash fields. */
    public String canonicalQueryString(Map<String, String> parameters) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (parameters != null) {
            parameters.forEach((key, value) -> {
                if (key != null && value != null
                        && !"vnp_SecureHash".equals(key)
                        && !"vnp_SecureHashType".equals(key)) {
                    sorted.put(key, value);
                }
            });
        }
        StringBuilder builder = new StringBuilder();
        sorted.forEach((key, value) -> {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(encode(key)).append('=').append(encode(value));
        });
        return builder.toString();
    }

    private void requireConfiguration() {
        if (!hasText(config.getTmnCode()) || !hasText(config.getHashSecret())
                || !hasText(config.getPayUrl()) || !hasText(config.getReturnUrl())) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "VNPay chưa được cấu hình. Cần VNPAY_TMN_CODE và VNPAY_HASH_SECRET.");
        }
    }

    private static String amountInSmallestUnit(long amount) {
        try {
            return Long.toString(Math.multiplyExact(amount, 100L));
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Số tiền quá lớn.");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String field) {
        if (!hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " là bắt buộc.");
        }
        return value.trim();
    }

    private static String normalizeIp(String ip) {
        if (!hasText(ip)) {
            return "127.0.0.1";
        }
        return truncate(ip.trim(), 45);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private record InstantHolder(java.time.Instant value) {
        private InstantHolder() {
            this(java.time.Instant.now());
        }
    }

    public record PaymentUrl(String orderNumber, long amount, String paymentUrl) {
    }

    public record GroupPaymentSessionResult(String status, UUID groupId, String groupNumber,
                                            long totalAmount, String currency, java.time.Instant expiresAt,
                                            String paymentUrl, String message) { }

    public record IpnResponse(String rspCode, String message) {
    }

    public record ReturnResult(
            boolean validSignature,
            String orderNumber,
            String responseCode,
            String message,
            String orderStatus,
            boolean orderPaid,
            boolean savedSession,
            String providerReference,
            com.utewedding.app.domain.PaymentAttemptStatus attemptStatus,
            UUID groupId,
            String groupNumber,
            String groupPaymentStatus) {
        public ReturnResult(boolean validSignature, String orderNumber, String responseCode, String message,
                            String orderStatus, boolean orderPaid, boolean savedSession,
                            String providerReference, com.utewedding.app.domain.PaymentAttemptStatus attemptStatus) {
            this(validSignature, orderNumber, responseCode, message, orderStatus, orderPaid, savedSession,
                    providerReference, attemptStatus, null, null, null);
        }
    }
}
