package com.utewedding.app.config;

import com.utewedding.app.domain.PaymentProviderEnvironment;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** VNPay sandbox settings. Never commit the HMAC secret; inject it at runtime. */
@Configuration
@ConfigurationProperties(prefix = "vnpay")
public class VNPayConfig {

    private String tmnCode = "";
    private String hashSecret = "";
    private String payUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private String returnUrl = "http://localhost:8080/payments/vnpay/return";
    private String ipnUrl = "http://localhost:8080/api/v1/payments/vnpay/ipn";
    private String apiUrl = "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction";
    private String environment = "";
    private String version = "2.1.0";
    private String command = "pay";
    private String currency = "VND";
    private String locale = "vn";
    private int expireMinutes = 15;

    public String getTmnCode() {
        return tmnCode;
    }

    public void setTmnCode(String tmnCode) {
        this.tmnCode = tmnCode;
    }

    public String getHashSecret() {
        return hashSecret;
    }

    public void setHashSecret(String hashSecret) {
        this.hashSecret = hashSecret;
    }

    public String getPayUrl() {
        return payUrl;
    }

    public void setPayUrl(String payUrl) {
        this.payUrl = payUrl;
    }

    public String getReturnUrl() {
        return returnUrl;
    }

    public void setReturnUrl(String returnUrl) {
        this.returnUrl = returnUrl;
    }

    public String getIpnUrl() {
        return ipnUrl;
    }

    public void setIpnUrl(String ipnUrl) {
        this.ipnUrl = ipnUrl;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public PaymentProviderEnvironment getProviderEnvironment() {
        String host = host(payUrl);
        PaymentProviderEnvironment configured = parseEnvironment(environment);
        if (configured != null) {
            if (configured == PaymentProviderEnvironment.SANDBOX) {
                return "sandbox.vnpayment.vn".equalsIgnoreCase(host) ? configured : null;
            }
            return host != null && !"sandbox.vnpayment.vn".equalsIgnoreCase(host) ? configured : null;
        }
        // The Sandbox URL is an explicit provider environment. Unknown/custom URLs
        // require an explicit VNPAY_ENVIRONMENT value; they are never guessed as live.
        return "sandbox.vnpayment.vn".equalsIgnoreCase(host)
                ? PaymentProviderEnvironment.SANDBOX : null;
    }

    private static PaymentProviderEnvironment parseEnvironment(String value) {
        if (value == null || value.isBlank()) return null;
        try { return PaymentProviderEnvironment.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return null; }
    }

    private static String host(String value) {
        try { return java.net.URI.create(value).getHost(); }
        catch (RuntimeException exception) { return null; }
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public int getExpireMinutes() {
        return expireMinutes;
    }

    public void setExpireMinutes(int expireMinutes) {
        this.expireMinutes = expireMinutes;
    }
}
