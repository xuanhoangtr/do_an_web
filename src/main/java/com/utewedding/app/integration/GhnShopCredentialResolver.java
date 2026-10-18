package com.utewedding.app.integration;

import com.utewedding.app.exception.BusinessConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Resolves only opaque account references to secrets supplied through protected process config. */
@Component
public final class GhnShopCredentialResolver {
    private static final Pattern REFERENCE = Pattern.compile("^credref:[A-Za-z0-9][A-Za-z0-9._/-]{2,180}$");
    private final Environment environment;

    public GhnShopCredentialResolver(Environment environment) {
        this.environment = environment;
    }

    public String resolve(String reference) {
        if (!isValidReference(reference)) {
            throw new BusinessConflictException("Tham chiếu thông tin xác thực GHN của cửa hàng không hợp lệ.");
        }
        String configured = environment.getProperty(propertyNameForReference(reference));
        if (configured == null || configured.isBlank() || configured.length() > 512
                || configured.indexOf('\r') >= 0 || configured.indexOf('\n') >= 0) {
            throw new BusinessConflictException(
                    "Chưa cấu hình thông tin xác thực GHN riêng cho cửa hàng trên máy chủ.");
        }
        return configured.trim();
    }

    /**
     * Property keys contain only a one-way digest, so an arbitrary database reference cannot
     * inject a property path. A process environment variable may use the relaxed uppercase form
     * {@code SHIPPING_CREDENTIALS_REFERENCES_<SHA256>}; the token value must remain outside source/DB.
     */
    public static String propertyNameForReference(String reference) {
        if (!isValidReference(reference)) {
            throw new IllegalArgumentException("Invalid credential reference");
        }
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(reference.getBytes(StandardCharsets.UTF_8)));
            return "shipping.credentials.references." + digest;
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static boolean isValidReference(String value) {
        return value != null && REFERENCE.matcher(value).matches() && !value.contains("..");
    }
}
