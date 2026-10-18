package com.utewedding.app.integration;

import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.service.port.ShopShippingQuoteProvider;
import com.utewedding.app.service.port.ShippingParcel;
import com.utewedding.app.service.port.ShippingProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Quote-only GHN Staging adapter that uses the selected shop's external ShopId and credential ref. */
@Component
public final class GhnShopShippingQuoteProvider implements ShopShippingQuoteProvider {
    private static final String STAGING_BASE =
            "https://dev-online-gateway.ghn.vn/shiip/public-api";
    private static final String PREVIEW_PATH = "/v2/shipping-order/preview";

    private final GhnShopCredentialResolver credentials;
    private final RestClient client;

    @Autowired
    public GhnShopShippingQuoteProvider(GhnShopCredentialResolver credentials, RestClient.Builder builder) {
        this(credentials, configuredClient(builder));
    }

    GhnShopShippingQuoteProvider(GhnShopCredentialResolver credentials, RestClient client) {
        this.credentials = credentials;
        this.client = client;
    }

    @Override
    public ShippingProvider.Quote quote(Request request) {
        int externalShopId = validate(request);
        String token = credentials.resolve(request.credentialReference());
        int serviceType = request.parcel().weightGrams() >= 20_000 ? 5 : 2;
        try {
            Map<String, Object> response = client.post().uri(STAGING_BASE + PREVIEW_PATH)
                    .header("Token", token)
                    .header("ShopId", Integer.toString(externalShopId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload(request, serviceType))
                    .retrieve()
                    .onStatus(HttpStatusCode::is3xxRedirection, (incoming, outgoing) -> {
                        throw quoteUnavailable();
                    })
                    .body(new ParameterizedTypeReference<>() { });
            Map<String, Object> data = map(response == null ? null : response.get("data"));
            long responseCode = number(response == null ? null : response.get("code"), -1);
            long fee = number(data.get("total_fee"), -1);
            if (responseCode != 200 || fee < 0) {
                throw quoteUnavailable();
            }
            return new ShippingProvider.Quote("STANDARD", Integer.toString(serviceType), fee, "VND",
                    null, Map.of("source", "GHN_STAGING_SHOP_PREVIEW"), Instant.now().plusSeconds(600));
        } catch (BusinessConflictException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // Never expose provider bodies, headers, endpoint parameters, or credential values.
            throw quoteUnavailable();
        } catch (RuntimeException exception) {
            throw quoteUnavailable();
        }
    }

    private int validate(Request request) {
        if (request == null || request.shopId() == null || request.bindingId() == null
                || request.providerAccountId() == null || request.pickup() == null
                || request.returnAddress() == null || request.destination() == null || request.parcel() == null
                || !supportedCatalogSource(request.catalogSource())) {
            throw quoteUnavailable();
        }
        Integer shopId = positiveShopId(request.externalShopId());
        if (shopId == null) throw quoteUnavailable();
        return shopId;
    }

    private Integer positiveShopId(String value) {
        if (value == null || !value.matches("^[0-9]{1,10}$")) return null;
        try {
            int shopId = Integer.parseInt(value);
            return shopId > 0 ? shopId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Map<String, Object> payload(Request request, int serviceType) {
        ShopShippingQuoteProvider.PickupAddress pickup = request.pickup();
        ShopShippingQuoteProvider.PickupAddress returnAddress = request.returnAddress();
        ShopShippingQuoteProvider.DestinationAddress destination = request.destination();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payment_type_id", 1);
        body.put("required_note", "CHOXEMHANGKHONGTHU");
        body.put("from_name", limit(pickup.contactName(), 1024));
        body.put("from_phone", digits(pickup.contactPhone()));
        body.put("from_address", limit(pickup.addressLine(), 1024));
        body.put("from_ward_name", pickup.wardName());
        body.put("from_province_name", pickup.provinceName());
        body.put("is_new_from_address", true);
        body.put("return_address", limit(returnAddress.addressLine(), 1024));
        body.put("return_phone", digits(returnAddress.contactPhone()));
        body.put("return_ward_name", limit(returnAddress.wardName(), 255));
        body.put("return_province_name", limit(returnAddress.provinceName(), 255));
        body.put("is_new_return_address", true);
        body.put("to_name", limit(destination.recipientName(), 1024));
        body.put("to_phone", digits(destination.phone()));
        body.put("to_address", limit(destination.addressLine(), 1024));
        body.put("to_ward_name", limit(destination.wardName(), 255));
        body.put("to_province_name", limit(destination.provinceName(), 255));
        body.put("is_new_to_address", true);
        body.put("weight", request.parcel().weightGrams());
        body.put("length", request.parcel().lengthCm());
        body.put("width", request.parcel().widthCm());
        body.put("height", request.parcel().heightCm());
        body.put("service_type_id", serviceType);
        body.put("content", limit(request.parcel().content(), 2000));
        body.put("cod_amount", 0);
        if (serviceType == 5) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (ShippingParcel.Item item : request.parcel().items()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", limit(item.name(), 512));
                row.put("code", limit(item.code(), 50));
                row.put("quantity", item.quantity());
                row.put("price", Math.max(0, item.price()));
                row.put("weight", item.weightGrams());
                row.put("length", item.lengthCm());
                row.put("width", item.widthCm());
                row.put("height", item.heightCm());
                items.add(row);
            }
            body.put("items", items);
        }
        return body;
    }

    private boolean supportedCatalogSource(String source) {
        return "LOCAL".equals(source) || "GHN_STAGING".equals(source);
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private long number(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(value == null ? "" : String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private String limit(String value, int maximum) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), maximum));
    }

    private BusinessConflictException quoteUnavailable() {
        return new BusinessConflictException(
                "GHN hiện chưa thể báo phí giao hàng cho cửa hàng. Kiểm tra lại địa chỉ hoặc thử lại sau.");
    }

    private static RestClient configuredClient(RestClient.Builder builder) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(stagingHttpClient());
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(requestFactory).build();
    }

    static HttpClient stagingHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
