package com.utewedding.app.integration;

import com.utewedding.app.config.MoMoConfig;
import com.utewedding.app.service.payment.PaymentGatewayClient;
import com.utewedding.app.service.payment.PaymentPayloads;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** MoMo query and refund. Refund uses a new orderId; requestId is the idempotency key. */
@Component
public class MomoPaymentGateway implements PaymentGatewayClient {
    private static final Logger log = LoggerFactory.getLogger(MomoPaymentGateway.class);
    private final MoMoConfig config;
    private final RestClient restClient;

    @Autowired
    public MomoPaymentGateway(MoMoConfig config, RestClient.Builder builder) {
        this(config, client(builder));
    }

    public MomoPaymentGateway(MoMoConfig config, RestClient restClient) {
        this.config = config;
        this.restClient = restClient;
    }

    @Override
    public String providerCode() {
        return "MOMO";
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        if (!configured()) {
            return new RefundResult(false, false, "CONFIG", "MoMo chưa được cấu hình.", null, null);
        }
        long transId;
        try {
            transId = Long.parseLong(command.providerTransactionId());
        } catch (RuntimeException exception) {
            return new RefundResult(false, false, "04", "Mã giao dịch MoMo không hợp lệ.", null, null);
        }
        String requestId = "RF" + PaymentPayloads.sha256(command.idempotencyKey()).substring(0, 20);
        String description = command.description() == null ? "Hoan tien " + command.merchantReference()
                : command.description();
        String raw = "accessKey=" + config.getAccessKey() + "&amount=" + command.amount()
                + "&description=" + description + "&orderId=" + requestId
                + "&partnerCode=" + config.getPartnerCode() + "&requestId=" + requestId
                + "&transId=" + transId;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerCode", config.getPartnerCode());
        body.put("orderId", requestId);
        body.put("requestId", requestId);
        body.put("amount", command.amount());
        body.put("transId", transId);
        body.put("lang", "vi");
        body.put("description", description);
        body.put("signature", sign(raw));
        Map<String, Object> response = post(refundUrl(), body);
        if (!correlates(response, requestId, requestId, command.amount())) {
            return new RefundResult(false, false, "CORRELATION", "MoMo response không khớp request.",
                text(response.get("transId")), PaymentPayloads.truncate(String.valueOf(response), 4000));
        }
        String code = code(response.get("resultCode"));
        String rawBody = PaymentPayloads.truncate(String.valueOf(response), 4000);
        boolean duplicate = "40".equals(code) || "41".equals(code);
        boolean success = "0".equals(code) || duplicate;
        return new RefundResult(success, duplicate, code, text(response.get("message")),
                text(response.get("transId")), rawBody);
    }

    @Override
    public QueryResult query(QueryCommand command) {
        if (!configured()) {
            return new QueryResult(QUERY_FAILED, null, null, "CONFIG", "MoMo chưa được cấu hình.", null);
        }
        String requestId = "Q" + PaymentPayloads.sha256(command.merchantReference() + ":" + System.nanoTime())
                .substring(0, 20);
        String raw = "accessKey=" + config.getAccessKey() + "&orderId=" + command.merchantReference()
                + "&partnerCode=" + config.getPartnerCode() + "&requestId=" + requestId;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerCode", config.getPartnerCode());
        body.put("requestId", requestId);
        body.put("orderId", command.merchantReference());
        body.put("lang", "vi");
        body.put("signature", sign(raw));
        Map<String, Object> response = post(queryUrl(), body);
        if (!correlates(response, requestId, command.merchantReference(), null)) {
            return new QueryResult(QUERY_FAILED, null, null, "CORRELATION",
                "MoMo response không khớp request.", PaymentPayloads.truncate(String.valueOf(response), 4000));
        }
        String code = code(response.get("resultCode"));
        String rawBody = PaymentPayloads.truncate(String.valueOf(response), 4000);
        if ("HTTP".equals(code)) {
            return new QueryResult(QUERY_FAILED, null, null, code, text(response.get("message")), rawBody);
        }
        String state = switch (code) {
            case "0" -> CAPTURED;
            case "1000", "7000", "9000" -> PENDING;
            case "1001", "1002", "1003", "1005", "1006" -> FAILED;
            case "42" -> NOT_FOUND;
            case "10", "99" -> QUERY_FAILED;
            default -> UNKNOWN;
        };
        Long amount = null;
        if (response.get("amount") instanceof Number number) amount = number.longValue();
        else if (response.get("amount") != null) {
            try { amount = Long.parseLong(response.get("amount").toString()); }
            catch (NumberFormatException ignored) { amount = null; }
        }
        return new QueryResult(state, text(response.get("transId")), amount, code, text(response.get("message")), rawBody);
    }

    public String sign(String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.getSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder out = new StringBuilder();
            for (byte value : mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))) {
                out.append(String.format("%02x", value));
            }
            return out.toString();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Không thể ký MoMo.", exception);
        }
    }

    private Map<String, Object> post(String url, Map<String, Object> body) {
        try {
            Map<String, Object> response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() { });
            return response == null ? Map.of("resultCode", "HTTP", "message", "MoMo tra ve rong") : response;
        } catch (RestClientException exception) {
            log.warn("MoMo transaction API call failed");
            return Map.of("resultCode", "HTTP", "message", "Khong the ket noi MoMo");
        }
    }

    private boolean configured() {
        return config.getPartnerCode() != null && !config.getPartnerCode().isBlank()
                && config.getAccessKey() != null && !config.getAccessKey().isBlank()
                && config.getSecretKey() != null && !config.getSecretKey().isBlank();
    }

    private boolean correlates(Map<String, Object> response, String requestId, String orderId, Long amount) {
        String partnerCode = text(response.get("partnerCode"));
        if (partnerCode.isEmpty() || config.getPartnerCode() == null
                || !config.getPartnerCode().equals(partnerCode)) return false;

        String responseRequestId = text(response.get("requestId"));
        if (responseRequestId.isEmpty() || requestId == null || !requestId.equals(responseRequestId)) return false;

        String responseOrderId = text(response.get("orderId"));
        if (responseOrderId.isEmpty() || orderId == null || !orderId.equals(responseOrderId)) return false;

        if (amount == null) return true;
        if (response.get("amount") == null) return false;
        return amount.longValue() == number(response.get("amount"));
    }

    private static long number(Object value) {
        try { return Long.parseLong(text(value)); }
        catch (NumberFormatException exception) { return Long.MIN_VALUE; }
    }

    private String queryUrl() {
        if (config.getQueryEndpoint() != null && !config.getQueryEndpoint().isBlank()) return config.getQueryEndpoint();
        return config.getEndpoint().replace("/create", "/query");
    }

    private String refundUrl() {
        if (config.getRefundEndpoint() != null && !config.getRefundEndpoint().isBlank()) return config.getRefundEndpoint();
        return config.getEndpoint().replace("/create", "/refund");
    }

    private static String code(Object value) {
        if (value == null) return "";
        if (value instanceof Number number) return Long.toString(number.longValue());
        return value.toString();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static RestClient client(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(35));
        return builder.requestFactory(factory).build();
    }
}
