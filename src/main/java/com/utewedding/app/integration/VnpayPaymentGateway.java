package com.utewedding.app.integration;

import com.utewedding.app.config.VNPayConfig;
import com.utewedding.app.service.payment.PaymentGatewayClient;
import com.utewedding.app.service.payment.PaymentPayloads;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** VNPay querydr and refund (API 2.1.0). Amounts on the wire are VND × 100. */
@Component
public class VnpayPaymentGateway implements PaymentGatewayClient {
    private static final Logger log = LoggerFactory.getLogger(VnpayPaymentGateway.class);
    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final VNPayConfig config;
    private final RestClient restClient;
    private final RestClient queryRestClient;

    @Autowired
    public VnpayPaymentGateway(VNPayConfig config, RestClient.Builder builder) {
        this(config, client(builder.clone()), queryClient(builder.clone()));
    }

    public VnpayPaymentGateway(VNPayConfig config, RestClient restClient) {
        this(config, restClient, restClient);
    }

    private VnpayPaymentGateway(VNPayConfig config, RestClient restClient, RestClient queryRestClient) {
        this.config = config;
        this.restClient = restClient;
        this.queryRestClient = queryRestClient;
    }

    @Override
    public String providerCode() {
        return "VNPAY";
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        if (!configured()) {
            return new RefundResult(false, false, "CONFIG", "VNPay chưa được cấu hình.", null, null);
        }
        if (command.providerTransactionId() == null || command.providerTransactionId().isBlank()
                || command.providerCreateDate() == null || command.providerCreateDate().isBlank()) {
            return new RefundResult(false, false, "04", "Thiếu mã hoặc ngày giao dịch VNPay.", null, null);
        }
        String requestId = PaymentPayloads.sha256(command.idempotencyKey()).substring(0, 32);
        String createDate = VN_DATE.format(ZonedDateTime.now(VN_ZONE));
        String amount = String.valueOf(Math.multiplyExact(command.amount(), 100L));
        String type = command.partial() ? "03" : "02";
        String orderInfo = truncate(command.description() == null ? "Hoan tien " + command.merchantReference()
                : command.description(), 255);
        String ip = truncate(command.clientIp() == null || command.clientIp().isBlank()
                ? "127.0.0.1" : command.clientIp(), 45);
        String actor = truncate(command.actor() == null || command.actor().isBlank() ? "system" : command.actor(), 20);
        String hash = checksum(String.join("|", requestId, config.getVersion(), "refund", config.getTmnCode(),
                type, command.merchantReference(), amount, command.providerTransactionId(),
                command.providerCreateDate(), actor, createDate, ip, orderInfo));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("vnp_RequestId", requestId);
        body.put("vnp_Version", config.getVersion());
        body.put("vnp_Command", "refund");
        body.put("vnp_TmnCode", config.getTmnCode());
        body.put("vnp_TransactionType", type);
        body.put("vnp_TxnRef", command.merchantReference());
        body.put("vnp_Amount", amount);
        body.put("vnp_TransactionNo", command.providerTransactionId());
        body.put("vnp_TransactionDate", command.providerCreateDate());
        body.put("vnp_CreateBy", actor);
        body.put("vnp_CreateDate", createDate);
        body.put("vnp_IpAddr", ip);
        body.put("vnp_OrderInfo", orderInfo);
        body.put("vnp_SecureHash", hash);
        return readRefund(post(body), command, type, amount);
    }

    @Override
    public QueryResult query(QueryCommand command) {
        if (!configured()) {
            return new QueryResult(QUERY_FAILED, null, null, "CONFIG", "VNPay chưa được cấu hình.", null);
        }
        if (command.providerCreateDate() == null || command.providerCreateDate().isBlank()) {
            return new QueryResult(QUERY_FAILED, null, null, "04", "Thiếu ngày tạo giao dịch VNPay.", null);
        }
        String requestId = PaymentPayloads.sha256("query:" + command.merchantReference() + ":"
                + System.nanoTime()).substring(0, 32);
        String createDate = VN_DATE.format(ZonedDateTime.now(VN_ZONE));
        String ip = truncate(command.clientIp() == null || command.clientIp().isBlank()
                ? "127.0.0.1" : command.clientIp(), 45);
        String orderInfo = truncate("Doi soat " + command.merchantReference(), 255);
        String hash = checksum(String.join("|", requestId, config.getVersion(), "querydr", config.getTmnCode(),
                command.merchantReference(), command.providerCreateDate(), createDate, ip, orderInfo));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("vnp_RequestId", requestId);
        body.put("vnp_Version", config.getVersion());
        body.put("vnp_Command", "querydr");
        body.put("vnp_TmnCode", config.getTmnCode());
        body.put("vnp_TxnRef", command.merchantReference());
        body.put("vnp_TransactionDate", command.providerCreateDate());
        body.put("vnp_CreateDate", createDate);
        body.put("vnp_IpAddr", ip);
        body.put("vnp_OrderInfo", orderInfo);
        body.put("vnp_SecureHash", hash);
        return readQuery(postQueryWithDeadline(body), command);
    }

    public String checksum(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(config.getHashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return String.format(Locale.ROOT, "%0128x", new BigInteger(1,
                    mac.doFinal(data.getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Không thể tạo chữ ký HMAC-SHA512.", exception);
        }
    }

    private Map<String, Object> post(Map<String, String> body) {
        return post(body, restClient);
    }

    private Map<String, Object> post(Map<String, String> body, RestClient client) {
        try {
            Map<String, Object> response = client.post()
                    .uri(apiUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() { });
            return response == null ? Map.of() : response;
        } catch (RestClientException exception) {
            log.warn("VNPay transaction API call failed");
            return Map.of("vnp_ResponseCode", "HTTP", "vnp_Message", "Khong the ket noi VNPay");
        }
    }

    private Map<String, Object> postQueryWithDeadline(Map<String, String> body) {
        FutureTask<Map<String, Object>> task = new FutureTask<>(() -> post(body, queryRestClient));
        Thread.ofVirtual().name("vnpay-query").start(task);
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            log.warn("VNPay transaction query exceeded its request deadline");
            return Map.of("vnp_ResponseCode", "HTTP", "vnp_Message", "VNPay query timed out");
        } catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return Map.of("vnp_ResponseCode", "HTTP", "vnp_Message", "VNPay query interrupted");
        } catch (ExecutionException exception) {
            log.warn("VNPay transaction query failed");
            return Map.of("vnp_ResponseCode", "HTTP", "vnp_Message", "VNPay query failed");
        }
    }

    private RefundResult readRefund(Map<String, Object> body, RefundCommand command,
                                    String transactionType, String amount) {
        String raw = PaymentPayloads.truncate(String.valueOf(body), 4000);
        if ("HTTP".equals(text(body.get("vnp_ResponseCode")))) {
            return new RefundResult(false, false, "HTTP", text(body.get("vnp_Message")), null, raw);
        }
        if (!correlates(body, "refund", command.merchantReference(), amount, transactionType)) {
            return new RefundResult(false, false, "CORRELATION", "Phản hồi VNPay không khớp request.",
                    text(body.get("vnp_TransactionNo")), raw);
        }
        if (!validResponseHash(body)) {
            return new RefundResult(false, false, text(body.get("vnp_ResponseCode")),
                    "Chữ ký phản hồi VNPay không hợp lệ.", text(body.get("vnp_TransactionNo")), raw);
        }
        String code = text(body.get("vnp_ResponseCode"));
        String status = text(body.get("vnp_TransactionStatus"));
        boolean duplicate = "94".equals(code);
        boolean accepted = ("00".equals(code) || duplicate) && !"09".equals(status);
        return new RefundResult(accepted, duplicate, code, text(body.get("vnp_Message")),
                text(body.get("vnp_TransactionNo")), raw);
    }

    private QueryResult readQuery(Map<String, Object> body, QueryCommand command) {
        String raw = PaymentPayloads.truncate(String.valueOf(body), 4000);
        String code = text(body.get("vnp_ResponseCode"));
        if ("HTTP".equals(code) || "CONFIG".equals(code)) {
            return new QueryResult(QUERY_FAILED, null, null, code, text(body.get("vnp_Message")), raw);
        }
        if (!correlates(body, "querydr", command.merchantReference(), null, null)) {
            return new QueryResult(QUERY_FAILED, text(body.get("vnp_TransactionNo")), null,
                    "CORRELATION", "Phản hồi VNPay không khớp request.", raw);
        }
        if (!validResponseHash(body)) {
            return new QueryResult(QUERY_FAILED, text(body.get("vnp_TransactionNo")), null, code,
                    "Chữ ký phản hồi VNPay không hợp lệ.", raw);
        }
        if (!"00".equals(code)) {
            String state = "01".equals(code) || "91".equals(code) ? NOT_FOUND : QUERY_FAILED;
            return new QueryResult(state, text(body.get("vnp_TransactionNo")), major(body.get("vnp_Amount")),
                    code, text(body.get("vnp_Message")), raw);
        }
        String state = switch (text(body.get("vnp_TransactionStatus"))) {
            case "00" -> CAPTURED;
            case "01" -> PENDING;
            case "02", "04" -> FAILED;
            case "05", "06" -> REFUND_PENDING;
            case "09" -> REFUND_REJECTED;
            default -> UNKNOWN;
        };
        return new QueryResult(state, text(body.get("vnp_TransactionNo")), major(body.get("vnp_Amount")),
                code, text(body.get("vnp_Message")), raw);
    }

    private boolean correlates(Map<String, Object> body, String command, String merchantReference,
                               String amount, String transactionType) {
        if (!config.getTmnCode().equals(text(body.get("vnp_TmnCode")))) return false;
        if (!command.equals(text(body.get("vnp_Command")))) return false;
        if (!merchantReference.equals(text(body.get("vnp_TxnRef")))) return false;
        if (amount != null && !amount.equals(text(body.get("vnp_Amount")))) return false;
        return transactionType == null || transactionType.equals(text(body.get("vnp_TransactionType")));
    }

    private boolean validResponseHash(Map<String, Object> body) {
        String provided = text(body.get("vnp_SecureHash"));
        if (provided.isBlank()) return false;
        String full = String.join("|",
                text(body.get("vnp_ResponseId")), text(body.get("vnp_Command")), text(body.get("vnp_ResponseCode")),
                text(body.get("vnp_Message")), text(body.get("vnp_TmnCode")), text(body.get("vnp_TxnRef")),
                text(body.get("vnp_Amount")), text(body.get("vnp_BankCode")), text(body.get("vnp_PayDate")),
                text(body.get("vnp_TransactionNo")), text(body.get("vnp_TransactionType")),
                text(body.get("vnp_TransactionStatus")), text(body.get("vnp_OrderInfo")),
                text(body.get("vnp_PromotionCode")), text(body.get("vnp_PromotionAmount")));
        String withoutPromotion = String.join("|",
                text(body.get("vnp_ResponseId")), text(body.get("vnp_Command")), text(body.get("vnp_ResponseCode")),
                text(body.get("vnp_Message")), text(body.get("vnp_TmnCode")), text(body.get("vnp_TxnRef")),
                text(body.get("vnp_Amount")), text(body.get("vnp_BankCode")), text(body.get("vnp_PayDate")),
                text(body.get("vnp_TransactionNo")), text(body.get("vnp_TransactionType")),
                text(body.get("vnp_TransactionStatus")), text(body.get("vnp_OrderInfo")));
        String expectedFull = checksum(full);
        String expectedShort = checksum(withoutPromotion);
        return constantTime(expectedFull, provided) || constantTime(expectedShort, provided);
    }

    private boolean configured() {
        return config.getTmnCode() != null && !config.getTmnCode().isBlank()
                && config.getHashSecret() != null && !config.getHashSecret().isBlank();
    }

    private String apiUrl() {
        String configured = config.getApiUrl();
        if (configured != null && !configured.isBlank()) return configured;
        return "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction";
    }

    private static Long major(Object minor) {
        if (minor == null || minor.toString().isBlank()) return null;
        try {
            long value = Long.parseLong(minor.toString());
            return value % 100L == 0 ? value / 100L : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static boolean constantTime(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static RestClient client(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(35));
        return builder.requestFactory(factory).build();
    }

    private static RestClient queryClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(factory).build();
    }
}
