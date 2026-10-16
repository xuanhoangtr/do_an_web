package com.utewedding.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** GHN integration is intentionally pinned to the Staging API host. */
@Component
public class GhnConfig {
    public static final String STAGING_API_BASE =
            "https://dev-online-gateway.ghn.vn/shiip/public-api";

    private final String token;
    private final int shopId;
    private final String webhookSecret;
    private final int defaultWeightGrams;
    private final int defaultLengthCm;
    private final int defaultWidthCm;
    private final int defaultHeightCm;
    private final String pickupProvinceId;
    private final String pickupWardId;
    @Value("${ghn.pickup-address:01 Võ Văn Ngân}")
    private String pickupAddress = "01 Võ Văn Ngân";
    @Value("${ghn.pickup-ward:Phường Thủ Đức}")
    private String pickupWard = "Phường Thủ Đức";
    @Value("${ghn.pickup-province:Hồ Chí Minh}")
    private String pickupProvince = "Hồ Chí Minh";

    @Autowired
    public GhnConfig(@Value("${ghn.token:}") String token,
                     @Value("${ghn.shop-id:0}") int shopId,
                     @Value("${ghn.webhook-secret:}") String webhookSecret,
                     @Value("${ghn.pickup-province-id:1000001}") String pickupProvinceId,
                     @Value("${ghn.pickup-ward-id:}") String pickupWardId,
                     @Value("${ghn.default-weight-grams:1000}") int defaultWeightGrams,
                     @Value("${ghn.default-length-cm:20}") int defaultLengthCm,
                     @Value("${ghn.default-width-cm:15}") int defaultWidthCm,
                     @Value("${ghn.default-height-cm:10}") int defaultHeightCm) {
        this.token = token == null ? "" : token.trim();
        this.shopId = shopId;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.pickupProvinceId = pickupProvinceId == null ? "" : pickupProvinceId.trim();
        this.pickupWardId = pickupWardId == null ? "" : pickupWardId.trim();
        this.defaultWeightGrams = positive(defaultWeightGrams, "ghn.default-weight-grams", 50_000);
        this.defaultLengthCm = positive(defaultLengthCm, "ghn.default-length-cm", 200);
        this.defaultWidthCm = positive(defaultWidthCm, "ghn.default-width-cm", 200);
        this.defaultHeightCm = positive(defaultHeightCm, "ghn.default-height-cm", 200);
    }

    /** Compatibility constructor for unit tests; production uses explicit pickup IDs from configuration. */
    public GhnConfig(String token, int shopId, String webhookSecret, int defaultWeightGrams,
                     int defaultLengthCm, int defaultWidthCm, int defaultHeightCm) {
        this(token, shopId, webhookSecret, "1000001", "1003646", defaultWeightGrams,
                defaultLengthCm, defaultWidthCm, defaultHeightCm);
    }

    public boolean isConfigured() { return !token.isBlank() && shopId > 0; }
    public boolean isWebhookConfigured() { return !webhookSecret.isBlank(); }
    public String token() { return token; }
    public int shopId() { return shopId; }
    public String webhookSecret() { return webhookSecret; }
    public int defaultWeightGrams() { return defaultWeightGrams; }
    public int defaultLengthCm() { return defaultLengthCm; }
    public int defaultWidthCm() { return defaultWidthCm; }
    public int defaultHeightCm() { return defaultHeightCm; }
    public String pickupAddress() { return pickupAddress; }
    public String pickupWard() { return pickupWard; }
    public String pickupProvince() { return pickupProvince; }
    public String pickupProvinceId() { return pickupProvinceId; }
    public String pickupWardId() { return pickupWardId; }

    private static int positive(int value, String property, int maximum) {
        if (value <= 0 || value > maximum) {
            throw new IllegalArgumentException(property + " must be positive and within supported bounds");
        }
        return value;
    }
}
