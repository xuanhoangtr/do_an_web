package com.utewedding.app.integration;

import com.utewedding.app.config.MoMoConfig;
import com.utewedding.app.observability.CommerceMetrics;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.PaymentEntity;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.PaymentRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.AuditLogPort;
import com.utewedding.app.service.port.PaymentProvider;
import com.utewedding.app.service.FulfillmentService;
import com.utewedding.app.service.payment.PaymentCallbackResult;
import com.utewedding.app.service.payment.PaymentIntegrityService;
import com.utewedding.app.service.payment.PaymentPayloads;
import com.utewedding.app.service.payment.VerifiedPaymentCallback;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MoMoService implements PaymentProvider {
    private static final Logger log = LoggerFactory.getLogger(MoMoService.class);
    private final MoMoConfig config;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final AuditLogPort audit;
    private final RestClient restClient;
    private final FulfillmentService fulfillmentService;
    private CommerceMetrics metrics = CommerceMetrics.noop();
    private PaymentIntegrityService paymentIntegrity;

    @Autowired
    public MoMoService(MoMoConfig config, OrderRepository orders, PaymentRepository payments,
                       AuditLogPort audit, FulfillmentService fulfillmentService,
                       RestClient.Builder restClientBuilder) {
        this.config = Objects.requireNonNull(config, "config");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.fulfillmentService = Objects.requireNonNull(fulfillmentService, "fulfillmentService");
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder").build();
    }

    @Autowired(required = false)
    void attachMetrics(CommerceMetrics metrics) {
        this.metrics = metrics == null ? CommerceMetrics.noop() : metrics;
    }

    @Autowired(required = false)
    void attachPaymentIntegrity(PaymentIntegrityService paymentIntegrity) {
        this.paymentIntegrity = paymentIntegrity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentRequest create(UUID userId, UUID orderId) {
        try {
            PaymentRequest created = createBody(userId, orderId);
            metrics.payment("MOMO", "started");
            return created;
        } catch (ResponseStatusException ex) {
            metrics.payment("MOMO", ex.getStatusCode().is5xxServerError() ? "error" : "rejected");
            if (ex.getStatusCode().is5xxServerError()) log.error("MoMo payment creation failed");
            throw ex;
        } catch (RuntimeException ex) {
            metrics.payment("MOMO", "error");
            log.error("MoMo payment creation failed");
            throw ex;
        }
    }

    private PaymentRequest createBody(UUID userId, UUID orderId) {
        OrderEntity order = orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng."));
        if (order.getCheckoutGroupId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Đơn hàng thuộc nhóm checkout. Hãy thanh toán qua nhóm checkout.");
        }
        if (order.getStatus() != OrderStatus.PENDING || order.getTotalAmount() <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đơn hàng không sẵn sàng thanh toán MoMo.");
        if (!paymentWindowOpen(order.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Thời hạn còn lại ngắn hơn thời lượng phiên thanh toán MoMo.");
        }
        requireConfig();
        String requestId = order.getOrderNumber();
        String raw = "accessKey=" + config.getAccessKey() + "&amount=" + order.getTotalAmount()
                + "&extraData=&ipnUrl=" + config.getIpnUrl() + "&orderId=" + requestId
                + "&orderInfo=Thanh toan " + requestId + "&partnerCode=" + config.getPartnerCode()
                + "&redirectUrl=" + config.getReturnUrl() + "&requestId=" + requestId
                + "&requestType=captureWallet";
        String signature = sign(raw);
        Map<String, Object> payload = Map.ofEntries(
                Map.entry("partnerCode", config.getPartnerCode()),
                Map.entry("requestId", requestId),
                Map.entry("amount", order.getTotalAmount()),
                Map.entry("orderId", requestId),
                Map.entry("orderInfo", "Thanh toan " + requestId),
                Map.entry("redirectUrl", config.getReturnUrl()),
                Map.entry("ipnUrl", config.getIpnUrl()),
                Map.entry("lang", "vi"),
                Map.entry("extraData", ""),
                Map.entry("requestType", "captureWallet"),
                Map.entry("signature", signature));
        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri(config.getEndpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Không thể kết nối cổng thanh toán MoMo.", exception);
        }
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MoMo trả về phản hồi rỗng.");
        }
        String resultCode = String.valueOf(response.getOrDefault("resultCode", "99"));
        if (!"0".equals(resultCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "MoMo từ chối yêu cầu: " + response.getOrDefault("message", "Unknown error"));
        }
        String payUrl = response.get("payUrl") == null ? null : response.get("payUrl").toString();
        if (payUrl == null || payUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MoMo không trả về payUrl.");
        }
        if (paymentIntegrity != null) {
            paymentIntegrity.registerAttempt(order, "MOMO", order.getTotalAmount(), order.getExpiresAt(),
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                            .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                            .format(java.time.Instant.now()));
        }
        // endpoint carries the provider redirect URL in the existing provider
        // contract, so the browser never receives the private MoMo credentials
        // or the server-side create endpoint.
        return new PaymentRequest("MOMO", requestId, order.getTotalAmount(), signature, payUrl);
    }

    public boolean paymentWindowOpen(java.time.Instant orderExpiry) {
        return orderExpiry == null || orderExpiry.isAfter(java.time.Instant.now()
                .plusSeconds(Math.max(1, config.getPaymentSessionMinutes()) * 60L + 30L));
    }

    @Override
    @Transactional
    public PaymentResult callback(Map<String, String> parameters) {
        return callback(parameters, null);
    }

    @Transactional
    public PaymentResult callback(Map<String, String> parameters, RequestContext context) {
        try {
            PaymentResult result = callbackBody(parameters, context);
            String outcome = momoOutcome(result);
            metrics.payment("MOMO", outcome);
            if ("rejected".equals(outcome)) log.error("MoMo callback rejected code={}", result.code());
            return result;
        } catch (RuntimeException ex) {
            metrics.payment("MOMO", "error");
            log.error("MoMo callback failed");
            throw ex;
        }
    }

    private PaymentResult callbackBody(Map<String, String> parameters, RequestContext context) {
        if (paymentIntegrity != null) return delegateCallback(parameters, context);
        if (!verifySignature(parameters)) return new PaymentResult(false, "97", "Invalid signature");
        String orderNumber = parameters.get("orderId");
        if (orderNumber == null || orderNumber.isBlank()) return new PaymentResult(false, "01", "Order not found");
        OrderEntity order = orders.findByOrderNumberForUpdate(orderNumber).orElse(null);
        if (order == null) return new PaymentResult(false, "01", "Order not found");
        if (!orderNumber.equals(parameters.get("requestId")))
            return new PaymentResult(false, "04", "Invalid request");
        long amount;
        try { amount = Long.parseLong(parameters.getOrDefault("amount", "-1")); }
        catch (RuntimeException ex) { return new PaymentResult(false, "04", "Invalid amount"); }
        if (amount != order.getTotalAmount()) return new PaymentResult(false, "04", "Invalid amount");
        if (!"0".equals(parameters.getOrDefault("resultCode", "99")))
            return new PaymentResult(true, "00", "Payment was not successful");
        String transactionId = parameters.get("transId");
        if (transactionId == null || transactionId.isBlank())
            return new PaymentResult(false, "04", "Invalid transaction");
        var previousPayment = payments.findByProviderTransactionId(transactionId);
        if (previousPayment.isPresent() && !order.getId().equals(previousPayment.get().getOrderId()))
            return new PaymentResult(false, "04", "Transaction already belongs to another order");
        if (order.getPaymentStatus() == PaymentStatus.SUCCEEDED || order.getStatus() == OrderStatus.PAID)
            return new PaymentResult(true, "00", "Already processed");
        if (order.getStatus() != OrderStatus.PENDING)
            return new PaymentResult(false, "02", "Order is not payable");
        OrderStatus previousOrderStatus = order.getStatus();
        PaymentStatus previousPaymentStatus = order.getPaymentStatus();
        order.transitionTo(OrderStatus.PAID); order.setPaymentStatus(PaymentStatus.SUCCEEDED);
        order.setPaidAt(java.time.Instant.now()); orders.save(order);
        PaymentEntity payment = previousPayment.orElseGet(() -> payments.findByOrderIdOrderByCreatedAtDesc(order.getId())
                .stream()
                .filter(row -> "MOMO".equalsIgnoreCase(row.getProvider())
                        && row.getStatus() == PaymentStatus.PENDING)
                .findFirst()
                .orElseGet(PaymentEntity::new));
        payment.setOrderId(order.getId()); payment.setProvider("MOMO");
        payment.setProviderTransactionId(transactionId); payment.setAmount(amount);
        payment.setCurrency(order.getCurrency()); payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setProcessedAt(java.time.Instant.now()); payments.save(payment);
        fulfillmentService.ensureForOrder(order.getId(), context);
        audit.record("ORDER_STATUS_CHANGED", null, "Order", order.getId(),
                Map.of("from", previousOrderStatus.name(), "to", order.getStatus().name(), "provider", "MOMO"),
                context);
        audit.record("PAYMENT_STATUS_CHANGED", null, "Payment", payment.getId(),
                Map.of("status", String.valueOf(previousPaymentStatus) + " -> " + payment.getStatus().name(),
                        "provider", "MOMO"), context);
        return new PaymentResult(true, "00", "Payment Success");
    }

    private PaymentResult delegateCallback(Map<String, String> parameters, RequestContext context) {
        if (!verifySignature(parameters)) {
            paymentIntegrity.recordInvalidSignature("MOMO", PaymentPayloads.canonical(parameters),
                    parameters == null ? null : parameters.get("orderId"),
                    parameters == null ? null : parameters.get("resultCode"));
            return new PaymentResult(false, "97", "Invalid signature");
        }
        PaymentCallbackResult result = paymentIntegrity.applyVerified(momoCallback(parameters), context);
        String message = switch (result.outcome()) {
            case APPLIED -> "Payment Success";
            case DUPLICATE -> "Already processed";
            case PAYMENT_FAILED, IGNORED -> "Payment was not successful";
            case LATE_CAPTURE -> "Late capture acknowledged";
            case REJECTED_SIGNATURE -> "Invalid signature";
            case AMOUNT_MISMATCH -> "Invalid amount";
            case ORDER_NOT_FOUND -> "Order not found";
            case CONFLICT -> "Order is not payable";
        };
        return new PaymentResult("00".equals(result.code()), result.code(), message);
    }

    private static VerifiedPaymentCallback momoCallback(Map<String, String> parameters) {
        Long amount = null;
        try {
            amount = Long.parseLong(parameters.getOrDefault("amount", ""));
        } catch (RuntimeException ignored) {
            amount = null;
        }
        String transactionId = parameters.get("transId");
        if (transactionId != null && transactionId.isBlank()) transactionId = null;
        boolean success = "0".equals(parameters.get("resultCode"));
        return new VerifiedPaymentCallback("MOMO", parameters.get("orderId"), transactionId,
                parameters.get("requestId"), amount, success, parameters.get("resultCode"),
                parameters.get("resultCode"), parameters.get("message"),
                PaymentPayloads.canonical(parameters), java.time.Instant.now());
    }

    private static String momoOutcome(PaymentResult result) {
        if ("00".equals(result.code()) && "Payment Success".equals(result.message())) return "succeeded";
        if ("00".equals(result.code()) && "Already processed".equals(result.message())) return "replay";
        if ("00".equals(result.code()) && result.message() != null && result.message().startsWith("Late")) return "late";
        if ("00".equals(result.code())) return "declined";
        if ("97".equals(result.code()) || "04".equals(result.code())) return "rejected";
        return "failed";
    }

    public boolean verifySignature(Map<String, String> parameters) {
        if (parameters == null || !hasText(parameters.get("signature"))) return false;
        String partnerCode = parameters.get("partnerCode");
        String orderId = parameters.get("orderId");
        String requestId = parameters.get("requestId");
        String amount = parameters.get("amount");
        String resultCode = parameters.get("resultCode");
        if (!hasText(partnerCode) || !hasText(orderId) || !hasText(requestId)
                || !hasText(amount) || !hasText(resultCode)
                || !partnerCode.equals(config.getPartnerCode())) return false;
        try {
            if (Long.parseLong(amount) <= 0 || Integer.parseInt(resultCode) < 0) return false;
        } catch (NumberFormatException invalidNumber) {
            return false;
        }
        if ("0".equals(resultCode) && !hasText(parameters.get("transId"))) return false;
        String raw = "accessKey=" + config.getAccessKey() + "&amount=" + amount
                + "&extraData=" + parameters.getOrDefault("extraData", "") + "&message=" + parameters.getOrDefault("message", "")
                + "&orderId=" + orderId + "&orderInfo=" + parameters.getOrDefault("orderInfo", "")
                + "&orderType=" + parameters.getOrDefault("orderType", "") + "&partnerCode=" + partnerCode
                + "&payType=" + parameters.getOrDefault("payType", "") + "&requestId=" + requestId
                + "&responseTime=" + parameters.getOrDefault("responseTime", "") + "&resultCode=" + resultCode
                + "&transId=" + parameters.getOrDefault("transId", "");
        return java.security.MessageDigest.isEqual(sign(raw).getBytes(StandardCharsets.UTF_8),
                parameters.get("signature").getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String sign(String raw) {
        requireConfig();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.getSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder out = new StringBuilder();
            for (byte value : mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))) out.append(String.format("%02x", value));
            return out.toString();
        } catch (GeneralSecurityException ex) { throw new IllegalStateException("Không thể ký MoMo.", ex); }
    }
    private void requireConfig() {
        if (config.getPartnerCode().isBlank() || config.getAccessKey().isBlank() || config.getSecretKey().isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MoMo chưa được cấu hình.");
    }
}
