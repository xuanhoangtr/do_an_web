package com.utewedding.app.config;

import com.utewedding.app.security.TrustedClientIpResolver;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails startup before a production instance can run with local or test settings. */
@Component
public class ProductionConfigurationValidator {
    private static final Set<String> VNPAY_PAYMENT_HOSTS = Set.of("pay.vnpay.vn");
    private static final Set<String> VNPAY_API_HOSTS = Set.of("merchant.vnpay.vn");

    private final Environment environment;

    public ProductionConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validateOnStartup() {
        if (!isProduction()) return;
        String trustedProxyCidrs = environment.getProperty("TRUSTED_PROXY_CIDRS", "").trim();
        if (trustedProxyCidrs.isBlank()) {
            fail("TRUSTED_PROXY_CIDRS must list the reverse proxy CIDRs in production");
        }
        try {
            TrustedClientIpResolver.validateConfiguredCidrs(trustedProxyCidrs);
        } catch (IllegalArgumentException exception) {
            fail("TRUSTED_PROXY_CIDRS must contain valid IP CIDRs");
        }
        requireText("spring.datasource.url", "database URL");
        requireText("spring.datasource.username", "database username");
        requireText("spring.datasource.password", "database password");
        requireText("security.jwt.private-key", "JWT private key");
        requireText("security.jwt.public-keys", "JWT public key ring");
        requireText("security.jwt.active-kid", "JWT active key id");
        requireSecret("security.audit.ip-salt", "audit salt", 32);
        requireText("security.field-encryption.active-key-id", "field encryption active key id");
        requireSecret("security.field-encryption.keys", "field encryption key ring", 32);
        requireSecret("security.risk.hmac-secret", "risk-correlation HMAC secret", 32);

        requireText("spring.mail.host", "SMTP host");
        requireText("spring.mail.username", "SMTP username");
        requireText("spring.mail.password", "SMTP password");
        requireText("cloudinary.cloud-name", "Cloudinary cloud name");
        requireText("cloudinary.api-key", "Cloudinary API key");
        requireText("cloudinary.api-secret", "Cloudinary API secret");
        requireText("vnpay.tmn-code", "VNPay merchant code");
        requireText("vnpay.hash-secret", "VNPay hash secret");
        requireText("momo.partner-code", "MoMo partner code");
        requireText("momo.access-key", "MoMo access key");
        requireText("momo.secret-key", "MoMo secret key");

        if (!"smtp".equalsIgnoreCase(value("mail.driver"))) {
            fail("mail.driver must be smtp in production");
        }
        if (!bool("security.cookie.secure") || bool("security.jwt.allow-ephemeral")) {
            fail("secure cookies must be enabled and ephemeral JWT keys disabled in production");
        }
        if (!bool("server.servlet.session.cookie.secure")) {
            fail("server.servlet.session.cookie.secure must be true in production");
        }
        if (!bool("server.servlet.session.cookie.http-only")) {
            fail("server.servlet.session.cookie.http-only must be true in production");
        }
        if (!"lax".equals(value("server.servlet.session.cookie.same-site").toLowerCase(Locale.ROOT))) {
            fail("server.servlet.session.cookie.same-site must be Lax in production");
        }
        if (!bool("spring.mail.properties.mail.smtp.auth")) {
            fail("SMTP authentication must be enabled in production");
        }
        if (!bool("spring.mail.properties.mail.smtp.starttls.enable")
                && !bool("spring.mail.properties.mail.smtp.ssl.enable")) {
            fail("SMTP must use STARTTLS or SMTPS in production");
        }

        for (String key : List.of("app.url", "app.frontend-url", "app.public-site-base-url")) {
            requireHttps(key);
        }
        for (String origin : commaValues("app.cors.allowed-origins", "app.websocket.allowed-origins")) {
            requireHttpsValue(origin, "origin");
        }
        requireHttpsRoute("vnpay.return-url", "/payments/vnpay/return");
        requireHttpsRoute("vnpay.ipn-url", "/api/v1/payments/vnpay/ipn");
        requireHttpsRoute("momo.return-url", "/payments/momo/return");
        requireHttpsRoute("momo.ipn-url", "/api/v1/payments/momo/ipn");
        requireHttps("vnpay.pay-url");
        requireHttps("vnpay.api-url");
        requireAllowedHost("vnpay.pay-url", VNPAY_PAYMENT_HOSTS);
        requireAllowedHost("vnpay.api-url", VNPAY_API_HOSTS);
        requireHttps("momo.endpoint");
        requireHttps("momo.query-endpoint");
        requireHttps("momo.refund-endpoint");

        if (bool("spring.flyway.baseline-on-migrate")) {
            fail("spring.flyway.baseline-on-migrate must be false in production");
        }
        String shippingProvider = value("shipping.default-provider").toUpperCase(Locale.ROOT);
        if (shippingProvider.isBlank()) fail("shipping.default-provider must be configured");
        if (shippingProvider.contains("FAKE") || shippingProvider.contains("SANDBOX")
                || shippingProvider.contains("TEST")) {
            fail("fake/sandbox shipping providers are forbidden in production");
        }
        if (!"INTERNAL".equals(shippingProvider)) {
            requireText("shipping.providers." + shippingProvider.toLowerCase(Locale.ROOT)
                    + ".webhook-secret", "shipping provider webhook secret");
        }
        int financeRetention = environment.getProperty("finance.media-retention-days", Integer.class, 0);
        if (financeRetention < 2555) {
            fail("finance.media-retention-days must be at least 2555");
        }
    }

    private boolean isProduction() {
        return environment.matchesProfiles("production")
                || "production".equalsIgnoreCase(value("app.environment"));
    }

    private void requireText(String key, String label) {
        if (value(key).isBlank()) fail("Missing production " + label + " (" + key + ")");
    }

    private void requireSecret(String key, String label, int minimumLength) {
        requireText(key, label);
        String secret = value(key);
        String normalized = secret.toLowerCase(Locale.ROOT);
        if (isSampleSecret(normalized)) {
            fail("Production " + label + " must not use a sample or placeholder value (" + key + ")");
        }
        if (secret.length() < minimumLength) {
            fail("Production " + label + " must be at least " + minimumLength + " characters (" + key + ")");
        }
    }

    private boolean isSampleSecret(String normalized) {
        return normalized.equals("secret") || normalized.equals("password")
                || normalized.equals("changeme") || normalized.contains("change-me")
                || normalized.startsWith("replace-with") || normalized.startsWith("your-")
                || normalized.contains("example-secret")
                || normalized.contains("local-risk-secret-change-me");
    }

    private void requireAllowedHost(String key, Set<String> allowedHosts) {
        try {
            String host = URI.create(value(key)).getHost();
            if (host == null || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                fail(key + " must use an approved production VNPay host");
            }
        } catch (IllegalArgumentException exception) {
            fail(key + " is not a valid URL");
        }
    }

    private void requireHttps(String key) {
        requireHttpsValue(value(key), key);
    }

    private void requireHttpsRoute(String key, String route) {
        requireHttps(key);
        try {
            if (!route.equals(URI.create(value(key)).getPath())) {
                fail(key + " must use route " + route);
            }
        } catch (IllegalArgumentException exception) {
            fail(key + " is not a valid URL");
        }
    }

    private void requireHttpsValue(String raw, String label) {
        if (raw == null || raw.isBlank()) fail("Missing production " + label);
        try {
            URI uri = URI.create(raw.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host.isBlank()
                    || host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("[::1]") || host.endsWith(".invalid")
                    || host.equals("test-payment.momo.vn") || host.endsWith("vnpayment.vn")) {
                fail(label + " must be a production HTTPS URL");
            }
        } catch (IllegalArgumentException exception) {
            fail(label + " is not a valid URL");
        }
    }

    private List<String> commaValues(String... keys) {
        return java.util.Arrays.stream(keys)
                .flatMap(key -> java.util.Arrays.stream(value(key).split(",")))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private boolean bool(String key) {
        return Boolean.parseBoolean(value(key));
    }

    private String value(String key) {
        return environment.getProperty(key, "").trim();
    }

    private void fail(String message) {
        throw new IllegalStateException("Invalid production configuration: " + message);
    }
}
