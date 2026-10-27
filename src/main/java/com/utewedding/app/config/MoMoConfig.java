package com.utewedding.app.config;

import com.utewedding.app.domain.PaymentProviderEnvironment;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "momo")
public class MoMoConfig {
    private String partnerCode = "";
    private String accessKey = "";
    private String secretKey = "";
    private String endpoint = "https://test-payment.momo.vn/v2/gateway/api/create";
    private String queryEndpoint = "https://test-payment.momo.vn/v2/gateway/api/query";
    private String refundEndpoint = "https://test-payment.momo.vn/v2/gateway/api/refund";
    private String environment = "";
    private String returnUrl = "http://localhost:8080/payments/momo/return";
    private String ipnUrl = "http://localhost:8080/api/v1/payments/momo/ipn";
    /** Must match the payment-session expiry configured for this merchant in MoMo. */
    private int paymentSessionMinutes = 15;
    public String getPartnerCode() { return partnerCode; }
    public void setPartnerCode(String value) { partnerCode = value; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String value) { accessKey = value; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String value) { secretKey = value; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String value) { endpoint = value; }
    public String getQueryEndpoint() { return queryEndpoint; }
    public void setQueryEndpoint(String value) { queryEndpoint = value; }
    public String getRefundEndpoint() { return refundEndpoint; }
    public void setRefundEndpoint(String value) { refundEndpoint = value; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String value) { environment = value; }
    public PaymentProviderEnvironment getProviderEnvironment() {
        String host;
        try { host = java.net.URI.create(endpoint).getHost(); }
        catch (RuntimeException exception) { host = null; }
        if (environment != null && !environment.isBlank()) {
            try {
                PaymentProviderEnvironment configured = PaymentProviderEnvironment.valueOf(
                        environment.trim().toUpperCase(java.util.Locale.ROOT));
                if (configured == PaymentProviderEnvironment.SANDBOX) {
                    return "test-payment.momo.vn".equalsIgnoreCase(host) ? configured : null;
                }
                return host != null && !"test-payment.momo.vn".equalsIgnoreCase(host) ? configured : null;
            }
            catch (IllegalArgumentException exception) { return null; }
        }
        return "test-payment.momo.vn".equalsIgnoreCase(host) ? PaymentProviderEnvironment.SANDBOX : null;
    }
    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String value) { returnUrl = value; }
    public String getIpnUrl() { return ipnUrl; }
    public void setIpnUrl(String value) { ipnUrl = value; }
    public int getPaymentSessionMinutes() { return paymentSessionMinutes; }
    public void setPaymentSessionMinutes(int value) { paymentSessionMinutes = value; }
}
