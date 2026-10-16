package com.utewedding.app.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.config.GhnConfig;
import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.dto.request.ShippingAddressRequest;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.service.AdministrativeAddressService;
import com.utewedding.app.service.port.ShippingProviderException;
import com.utewedding.app.service.port.ShippingParcel;
import com.utewedding.app.service.port.ShippingProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** GHN shipping integration. Network requests are pinned to Staging by GhnConfig. */
@Component
public class GhnShippingProvider implements ShippingProvider {
    private static final String CREATE = "/v2/shipping-order/create";
    private static final String PREVIEW = "/v2/shipping-order/preview";
    private static final String CANCEL = "/v2/switch-status/cancel";
    private static final String DETAIL = "/v2/shipping-order/detail";
    private static final String DETAIL_BY_CLIENT_CODE = "/v2/shipping-order/detail-by-client-code";
    private static final String WEBHOOK_SECRET_HEADER = "X-GHN-Webhook-Secret";

    private final GhnConfig config;
    private final AdministrativeAddressService addresses;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final GhnShopCredentialResolver shopCredentials;

    public GhnShippingProvider(GhnConfig config, AdministrativeAddressService addresses,
                               RestClient.Builder builder, ObjectMapper mapper) {
        this(config, addresses, configuredClient(builder), mapper, null);
    }

    GhnShippingProvider(GhnConfig config, AdministrativeAddressService addresses,
                        RestClient client, ObjectMapper mapper) {
        this(config, addresses, client, mapper, null);
    }

    @Autowired
    GhnShippingProvider(GhnConfig config, AdministrativeAddressService addresses,
                        RestClient.Builder builder, ObjectMapper mapper,
                        GhnShopCredentialResolver shopCredentials) {
        this(config, addresses, configuredClient(builder), mapper, shopCredentials);
    }

    GhnShippingProvider(GhnConfig config, AdministrativeAddressService addresses,
                        RestClient client, ObjectMapper mapper, GhnShopCredentialResolver shopCredentials) {
        this.config = config;
        this.addresses = addresses;
        this.client = client;
        this.mapper = mapper;
        this.shopCredentials = shopCredentials;
    }

    @Override public String code() { return "GHN"; }

    @Override public Set<Capability> capabilities() {
        return Set.of(Capability.QUOTE, Capability.CREATE, Capability.CANCEL,
                Capability.TRACK, Capability.WEBHOOK);
    }

    @Override
    public boolean matchesPlatformShopId(String providerShopId) {
        return config.shopId() > 0 && providerShopId != null && !providerShopId.isBlank()
                && Integer.toString(config.shopId()).equals(providerShopId.trim());
    }

    @Override
    public Quote quote(QuoteRequest request) {
        ShippingParcel parcel = requireParcel(request.parcel());
        int serviceType = serviceType(null, parcel);
        Map<String, Object> response = post(PREVIEW, orderPayload(request.destination(), parcel,
                serviceType, request.codAmount(), null, addresses.activeCatalogSource(), null), false);
        Map<String, Object> data = object(response.get("data"));
        long fee = number(data.get("total_fee"), -1);
        if (fee < 0) throw new IllegalStateException("GHN không trả về phí giao hàng hợp lệ.");
        String expectedTime = text(data.get("expected_delivery_time"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "GHN_STAGING_PREVIEW");
        metadata.put("expectedDeliveryTime", expectedTime);
        metadata.put("feeBreakdown", object(data.get("fee")));
        metadata.put("parcel", parcel);
        return new Quote("STANDARD", Integer.toString(serviceType), fee, "VND", null,
                Map.copyOf(metadata), Instant.now().plusSeconds(600));
    }

    @Override
    public ShipmentResult create(CreateShipment request) {
        ShippingParcel parcel = requireParcel(request.parcel());
        int serviceType = serviceType(request.serviceCode(), parcel);
        String clientOrderCode = clientOrderCode(request.idempotencyKey());
        Map<String, Object> response = post(CREATE, orderPayload(request.destination(), parcel,
                serviceType, request.codAmount(), clientOrderCode, request.addressCatalogSource(), request.context()),
                true, request.context());
        Map<String, Object> data = object(response.get("data"));
        String orderCode = text(data.get("order_code"));
        if (orderCode.isBlank()) {
            throw new ShippingProviderException(
                    "GHN đã nhận phản hồi nhưng chưa trả mã vận đơn; hệ thống sẽ tra cứu theo mã yêu cầu trước khi thử lại.",
                    ShippingProviderException.Outcome.UNKNOWN,
                    responseDiagnostics(200, response), false);
        }
        ShipmentResult verifiedCreate = verifyCreatedShipment(new VerificationRequest(orderCode,
                request.idempotencyKey(), request.codAmount(), request.destination(), request.context()));
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "GHN_STAGING");
        metadata.put("orderCode", orderCode);
        metadata.put("clientOrderCode", clientOrderCode);
        metadata.put("serviceTypeId", serviceType);
        long carrierFee = number(data.get("total_fee"), -1);
        if (carrierFee >= 0) metadata.put("carrierFee", carrierFee);
        metadata.put("feeBreakdown", object(data.get("fee")));
        metadata.putAll(verifiedCreate.metadata());
        return new ShipmentResult(orderCode, clientOrderCode, orderCode, null,
                verifiedCreate.status(), Map.copyOf(metadata));
    }

    @Override
    public ShipmentResult cancel(OperationRequest request) {
        if (request.providerShipmentId() == null || request.providerShipmentId().isBlank()) {
            throw new IllegalArgumentException("Thiếu mã vận đơn GHN để hủy.");
        }
        Map<String, Object> payload = Map.of("order_codes", List.of(request.providerShipmentId()),
                "reason_code", "GHN-CANCEL-OTHER", "reason", "Order cancelled by shop");
        Map<String, Object> response = post(CANCEL, payload, true, request.context());
        Object result = response.get("data");
        if (!(result instanceof List<?> rows) || rows.isEmpty()
                || !Boolean.TRUE.equals(object(rows.get(0)).get("result"))) {
            throw new IllegalStateException("GHN không chấp nhận hủy vận đơn ở trạng thái hiện tại.");
        }
        return new ShipmentResult(request.providerShipmentId(), request.providerShipmentId(),
                request.trackingNumber(), null, FulfillmentStatus.CANCELLED,
                Map.of("source", "GHN_STAGING", "cancelled", true));
    }

    @Override
    public ShipmentResult requestPickup(OperationRequest request) {
        throw new UnsupportedOperationException("GHN tự tiếp nhận lấy hàng sau khi tạo vận đơn.");
    }

    @Override
    public TrackingResult track(OperationRequest request) {
        if (request.providerShipmentId() == null || request.providerShipmentId().isBlank()) {
            throw new IllegalArgumentException("Thiếu mã vận đơn GHN để tra cứu.");
        }
        Map<String, Object> response = get(DETAIL, "order_code", request.providerShipmentId(), request.context());
        Map<String, Object> data = object(response.get("data"));
        verifyContextIdentity(data, request.context());
        if (!request.providerShipmentId().equals(firstText(data, "order_code", "OrderCode"))) {
            throw new IllegalArgumentException("GHN Detail không khớp mã vận đơn đã lưu.");
        }
        String status = text(data.get("status"));
        return new TrackingResult(status(status), 0, instant(data.get("updated_date")),
                detailDiagnostics(data));
    }

    @Override
    public ClientRequestLookup findByClientRequestId(String operation, String clientRequestId) {
        return findByClientRequestId(operation, clientRequestId, null);
    }

    @Override
    public ClientRequestLookup findByClientRequestId(String operation, String clientRequestId,
                                                      GhnShipmentContext context) {
        if (!"CREATE".equals(operation)) return new ClientRequestLookup(LookupStatus.UNKNOWN, null);
        try {
            Map<String, Object> response = get(DETAIL_BY_CLIENT_CODE, "client_order_code",
                    clientOrderCode(clientRequestId), context);
            Map<String, Object> data = object(response.get("data"));
            String orderCode = text(data.get("order_code"));
            if (orderCode.isBlank()) return new ClientRequestLookup(LookupStatus.UNKNOWN, null);
            verifyContextIdentity(data, context);
            if (!clientOrderCode(clientRequestId).equals(firstText(data, "client_order_code", "ClientOrderCode"))) {
                return new ClientRequestLookup(LookupStatus.UNKNOWN, null);
            }
            return new ClientRequestLookup(LookupStatus.FOUND,
                    new ShipmentResult(orderCode, clientOrderCode(clientRequestId), orderCode, null,
                            status(text(data.get("status"))),
                            Map.of("source", "GHN_STAGING_LOOKUP")));
        } catch (ShippingProviderException exception) {
            return new ClientRequestLookup(exception.notFound()
                    ? LookupStatus.NOT_FOUND : LookupStatus.UNKNOWN, null);
        }
    }

    @Override
    public ShipmentResult verifyCreatedShipment(VerificationRequest request) {
        if (request == null || request.providerShipmentId() == null
                || request.providerShipmentId().isBlank()) {
            throw new IllegalArgumentException("Cần mã vận đơn GHN để đối chiếu.");
        }
        String expectedClientCode = clientOrderCode(request.idempotencyKey());
        Map<String, Object> response = get(DETAIL, "order_code", request.providerShipmentId(), request.context());
        Map<String, Object> data = object(response.get("data"));
        String orderCode = firstText(data, "order_code", "OrderCode");
        String actualClientCode = firstText(data, "client_order_code", "ClientOrderCode");
        long shopId = number(firstValue(data, "shop_id", "ShopId"), -1);
        long codAmount = number(firstValue(data, "cod_amount", "CODAmount"), -1);
        String recipientName = firstText(data, "to_name", "ToName");
        String recipientPhone = firstText(data, "to_phone", "ToPhone");
        String providerStatus = firstText(data, "status", "Status");
        FulfillmentStatus shipmentStatus = status(providerStatus);

        boolean valid = request.providerShipmentId().equals(orderCode)
                && expectedShopId(request.context()) == shopId
                && expectedClientCode.equals(actualClientCode)
                && request.expectedCodAmount() == codAmount
                && (request.context() == null || request.context().expectedCodAmount() == request.expectedCodAmount())
                && request.destination() != null
                && comparable(recipientName).equals(comparable(request.destination().recipientName()))
                && phone(recipientPhone).equals(phone(request.destination().phone()))
                && shipmentStatus != null;
        if (!valid) {
            throw new IllegalArgumentException(
                    "Thông tin mã GHN không khớp shop, mã yêu cầu, người nhận, tiền COD hoặc trạng thái của đơn. Chưa ghi nhận vận đơn.");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "GHN_STAGING_DETAIL_VERIFIED");
        metadata.put("orderCode", orderCode);
        metadata.put("clientOrderCode", actualClientCode);
        metadata.put("shopId", shopId);
        metadata.put("codAmount", codAmount);
        metadata.put("providerStatus", providerStatus);
        return new ShipmentResult(orderCode, actualClientCode, orderCode, null,
                shipmentStatus, Map.copyOf(metadata));
    }

    @Override
    public WebhookEvent verifyAndParseWebhook(Map<String, String> headers, byte[] rawBody) {
        if (!config.isWebhookConfigured()) {
            throw new IllegalStateException("GHN webhook secret chưa được cấu hình.");
        }
        String supplied = header(headers, WEBHOOK_SECRET_HEADER);
        if (supplied == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                config.webhookSecret().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("GHN webhook không hợp lệ.");
        }
        try {
            Map<String, Object> body = mapper.readValue(rawBody, new TypeReference<>() { });
            String orderCode = text(body.get("OrderCode"));
            String type = text(body.get("Type"));
            String providerStatus = text(body.get("Status"));
            String time = text(body.get("Time"));
            String providerShopId = firstText(body, "ShopId", "ShopID", "shop_id");
            if (orderCode.isBlank() || type.isBlank() || time.isBlank()) {
                throw new IllegalArgumentException("GHN webhook thiếu mã đơn hoặc thời điểm.");
            }
            if (providerShopId.isBlank()) {
                throw new IllegalArgumentException("GHN webhook thiếu ShopId để xác định tài khoản cửa hàng.");
            }
            Instant eventAt = webhookInstant(time);
            String eventId = orderCode + "|" + type + "|" + time;
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("OrderCode", orderCode);
            safe.put("ClientOrderCode", text(body.get("ClientOrderCode")));
            safe.put("Type", type);
            safe.put("Status", providerStatus);
            safe.put("ReasonCode", text(body.get("ReasonCode")));
            safe.put("Time", time);
            safe.put("ShopId", providerShopId);
            FulfillmentStatus canonical = "create".equalsIgnoreCase(type)
                    || "switch_status".equalsIgnoreCase(type) ? status(providerStatus) : null;
            return new WebhookEvent(eventId, orderCode, providerStatus, canonical,
                    null, eventAt, Map.copyOf(safe), null, null, providerShopId);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("GHN webhook JSON không hợp lệ.");
        }
    }

    private Map<String, Object> orderPayload(ShippingAddressRequest destination, ShippingParcel parcel,
                                             int serviceType, long codAmount, String clientOrderCode,
                                             String addressCatalogSource, GhnShipmentContext context) {
        if (destination == null) throw new IllegalArgumentException("Thiếu địa chỉ giao hàng.");
        if (!supportedCatalogSource(addressCatalogSource)) {
            throw new BusinessConflictException(
                    "Nguồn danh mục địa chỉ không được hỗ trợ cho báo giá hoặc vận đơn GHN.");
        }
        if (codAmount > 50_000_000) {
            throw new BusinessConflictException("GHN chỉ hỗ trợ thu hộ COD tối đa 50.000.000đ mỗi đơn.");
        }
        if (context == null && (config.pickupProvinceId().isBlank() || config.pickupWardId().isBlank())) {
            throw new BusinessConflictException("Chưa cấu hình mã tỉnh/phường GHN cho địa chỉ lấy và hoàn hàng.");
        }
        ShippingAddressRequest pickupAddress = context == null ? null : context.pickupAddress();
        ShippingAddressRequest returnAddress = context == null ? null : context.returnAddress();
        if (context != null && (pickupAddress == null || returnAddress == null)) {
            throw new BusinessConflictException("Snapshot thiếu địa chỉ lấy hoặc hoàn hàng GHN.");
        }
        String originCatalogSource = context == null
                ? AdministrativeAddressService.GHN_STAGING : addressCatalogSource;
        AdministrativeAddressService.ResolvedAddress pickup = context == null
                ? addresses.resolve(originCatalogSource,
                        config.pickupProvinceId(), config.pickupWardId())
                : addresses.resolve(originCatalogSource,
                        pickupAddress.provinceCode(), pickupAddress.wardCode());
        AdministrativeAddressService.ResolvedAddress returnLocation = context == null ? pickup
                : addresses.resolve(originCatalogSource,
                        returnAddress.provinceCode(), returnAddress.wardCode());
        AdministrativeAddressService.ResolvedAddress recipient = addresses.resolve(
                addressCatalogSource, destination.provinceCode(), destination.wardCode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payment_type_id", 1); // The buyer already pays shipping through the website checkout.
        body.put("required_note", "CHOXEMHANGKHONGTHU");
        body.put("from_name", context == null ? "UTEWedding" : limit(pickupAddress.recipientName(), 1024));
        if (context != null) body.put("from_phone", phone(pickupAddress.phone()));
        body.put("from_address", context == null ? config.pickupAddress()
                : limit(pickupAddress.addressLine(), 1024));
        body.put("from_ward_name", pickup.wardName());
        body.put("from_province_name", pickup.provinceName());
        body.put("is_new_from_address", true);
        if (context != null) body.put("return_phone", phone(returnAddress.phone()));
        body.put("return_address", context == null ? config.pickupAddress()
                : limit(returnAddress.addressLine(), 1024));
        body.put("return_ward_name", returnLocation.wardName());
        body.put("return_province_name", returnLocation.provinceName());
        body.put("is_new_return_address", true);
        body.put("to_name", limit(destination.recipientName(), 1024));
        body.put("to_phone", phone(destination.phone()));
        body.put("to_address", limit(destination.addressLine(), 1024));
        body.put("to_ward_name", recipient.wardName());
        body.put("to_province_name", recipient.provinceName());
        body.put("is_new_to_address", true);
        body.put("weight", parcel.weightGrams());
        body.put("length", parcel.lengthCm());
        body.put("width", parcel.widthCm());
        body.put("height", parcel.heightCm());
        body.put("service_type_id", serviceType);
        body.put("content", limit(parcel.content(), 2000));
        body.put("cod_amount", Math.max(0, codAmount));
        if (clientOrderCode != null) body.put("client_order_code", clientOrderCode);
        if (serviceType == 5) {
            body.put("items", parcel.items().stream().map(this::itemPayload).toList());
        }
        return body;
    }

    private boolean supportedCatalogSource(String catalogSource) {
        return AdministrativeAddressService.LOCAL.equals(catalogSource)
                || AdministrativeAddressService.GHN_STAGING.equals(catalogSource);
    }

    private Map<String, Object> itemPayload(ShippingParcel.Item item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", limit(item.name(), 512));
        body.put("code", limit(item.code(), 50));
        body.put("quantity", item.quantity());
        body.put("price", Math.max(0, item.price()));
        body.put("weight", item.weightGrams());
        body.put("length", item.lengthCm());
        body.put("width", item.widthCm());
        body.put("height", item.heightCm());
        return body;
    }

    private Map<String, Object> post(String path, Map<String, Object> payload, boolean sideEffectPossible) {
        return post(path, payload, sideEffectPossible, null);
    }

    private Map<String, Object> post(String path, Map<String, Object> payload, boolean sideEffectPossible,
                                    GhnShipmentContext context) {
        requireConfigured(context);
        try {
            Map<String, Object> response = client.post().uri(GhnConfig.STAGING_API_BASE + path)
                    .header("Token", token(context))
                    .header("ShopId", Long.toString(expectedShopId(context)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            return requireSuccess(response, 200, sideEffectPossible);
        } catch (RestClientResponseException exception) {
            throw providerFailure(exception.getStatusCode().value(), exception.getResponseBodyAsString(),
                    exception.getStatusText(), sideEffectPossible);
        } catch (RestClientException exception) {
            throw new ShippingProviderException(
                    sideEffectPossible
                            ? "Mất kết nối trong lúc gửi yêu cầu GHN; chưa xác định được vận đơn đã tạo hay chưa, hệ thống sẽ tra cứu trước khi thử lại."
                            : "Không thể kết nối GHN Staging để kiểm tra yêu cầu.",
                    sideEffectPossible ? ShippingProviderException.Outcome.UNKNOWN
                            : ShippingProviderException.Outcome.REJECTED,
                    Map.of("httpStatus", 0, "message", "Connection failed"), false);
        }
    }

    private Map<String, Object> get(String path, String parameter, String value) {
        return get(path, parameter == null || value == null ? Map.of() : Map.of(parameter, value));
    }

    private Map<String, Object> get(String path, String parameter, String value, GhnShipmentContext context) {
        return get(path, parameter == null || value == null ? Map.of() : Map.of(parameter, value), context);
    }

    private Map<String, Object> get(String path, Map<String, String> parameters) {
        return get(path, parameters, null);
    }

    private Map<String, Object> get(String path, Map<String, String> parameters, GhnShipmentContext context) {
        requireConfigured(context);
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(GhnConfig.STAGING_API_BASE + path);
        parameters.forEach(uriBuilder::queryParam);
        String uri = uriBuilder.build().encode().toUriString();
        try {
            Map<String, Object> response = client.get().uri(uri)
                    .header("Token", token(context))
                    .header("ShopId", Long.toString(expectedShopId(context)))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            return requireSuccess(response, 200, false);
        } catch (RestClientResponseException exception) {
            throw providerFailure(exception.getStatusCode().value(), exception.getResponseBodyAsString(),
                    exception.getStatusText(), false);
        } catch (RestClientException exception) {
            throw new ShippingProviderException("Không thể kết nối GHN Staging để tra cứu.",
                    ShippingProviderException.Outcome.REJECTED,
                    Map.of("httpStatus", 0, "message", "Connection failed"), false);
        }
    }

    private Map<String, Object> requireSuccess(Map<String, Object> response, int httpStatus,
                                               boolean sideEffectPossible) {
        if (response == null || number(response.get("code"), 0) != 200) {
            throw providerFailure(httpStatus, response == null ? "" : toJson(response),
                    "GHN Staging không chấp nhận yêu cầu.", sideEffectPossible);
        }
        return response;
    }

    private void requireConfigured() {
        if (!config.isConfigured()) {
            throw new BusinessConflictException("GHN Staging chưa được cấu hình; vui lòng liên hệ cửa hàng.");
        }
    }

    private void requireConfigured(GhnShipmentContext context) {
        if (context == null) {
            requireConfigured();
            return;
        }
        if (!"STAGING".equalsIgnoreCase(context.environment()) || shopCredentials == null) {
            throw new BusinessConflictException("GHN Staging chưa được cấu hình cho tài khoản của cửa hàng.");
        }
        shopCredentials.resolve(context.credentialReference());
    }

    private String token(GhnShipmentContext context) {
        if (context == null) return config.token();
        if (!"STAGING".equalsIgnoreCase(context.environment()) || shopCredentials == null) {
            throw new BusinessConflictException("GHN chỉ được phép gọi bằng context Staging đã lưu.");
        }
        return shopCredentials.resolve(context.credentialReference());
    }

    private long expectedShopId(GhnShipmentContext context) {
        if (context == null) return config.shopId();
        try { return Long.parseLong(context.externalShopId()); }
        catch (RuntimeException e) { throw new BusinessConflictException("ShopId GHN trong snapshot không hợp lệ."); }
    }

    private void verifyContextIdentity(Map<String, Object> detail, GhnShipmentContext context) {
        if (context == null) return;
        long actualShopId = number(firstValue(detail, "shop_id", "ShopId", "ShopID"), -1);
        if (actualShopId < 0 || actualShopId != expectedShopId(context)) {
            throw new IllegalArgumentException("GHN Detail trả về ShopId không khớp context shipment đã lưu.");
        }
        long actualCod = number(firstValue(detail, "cod_amount", "CODAmount"), -1);
        if (actualCod < 0 || actualCod != context.expectedCodAmount()) {
            throw new IllegalArgumentException("GHN Detail trả về COD không khớp context shipment đã lưu.");
        }
    }

    private ShippingParcel requireParcel(ShippingParcel parcel) {
        if (parcel == null || parcel.items().isEmpty() || parcel.weightGrams() <= 0
                || parcel.lengthCm() <= 0 || parcel.widthCm() <= 0 || parcel.heightCm() <= 0) {
            throw new IllegalArgumentException("Thiếu thông tin khối lượng hoặc kích thước kiện GHN.");
        }
        return parcel;
    }

    private int serviceType(String serviceCode, ShippingParcel parcel) {
        if ("2".equals(serviceCode) || "5".equals(serviceCode)) return Integer.parseInt(serviceCode);
        return parcel.weightGrams() >= 20_000 ? 5 : 2;
    }

    private String clientOrderCode(String idempotencyKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    String.valueOf(idempotencyKey).getBytes(StandardCharsets.UTF_8));
            return "WTGHN" + java.util.HexFormat.of().formatHex(digest).substring(0, 40);
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo mã đối chiếu GHN.");
        }
    }

    private ShippingProviderException providerFailure(int httpStatus, String body, String fallback,
                                                       boolean sideEffectPossible) {
        Map<String, Object> response = parseJsonObject(body);
        long code = number(response.get("code"), 0);
        String message = safeText(firstText(response, "message", "Message"), 300);
        String codeMessage = safeText(firstText(response, "code_message", "codeMessage"), 120);
        String codeMessageValue = safeText(firstText(response, "code_message_value", "codeMessageValue"), 300);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("httpStatus", httpStatus);
        if (code > 0) diagnostics.put("code", code);
        if (!message.isBlank()) diagnostics.put("message", message);
        if (!codeMessage.isBlank()) diagnostics.put("code_message", codeMessage);
        if (!codeMessageValue.isBlank()) diagnostics.put("code_message_value", codeMessageValue);
        boolean rejected = (httpStatus >= 400 && httpStatus < 500) || (code >= 400 && code < 500);
        boolean notFound = (httpStatus == 404 || httpStatus == 400)
                && (message.toLowerCase(Locale.ROOT).contains("order not found")
                || message.toLowerCase(Locale.ROOT).contains("đơn hàng không tồn tại"));
        String userMessage;
        if ("TO_ADDRESS_CONFLICT".equalsIgnoreCase(codeMessage)) {
            userMessage = "GHN Staging từ chối địa chỉ nhận (TO_ADDRESS_CONFLICT): "
                    + (codeMessageValue.isBlank() ? "địa chỉ nhận không hợp lệ" : codeMessageValue)
                    + ". Kiểm tra lại địa chỉ chi tiết của người nhận."
                    + (code > 0 ? " · code " + code : "")
                    + (httpStatus > 0 ? " · HTTP " + httpStatus : "");
        } else {
            String reason = !codeMessageValue.isBlank() ? codeMessageValue
                    : !message.isBlank() ? message : fallback;
            userMessage = "GHN Staging từ chối yêu cầu: " + reason
                    + (codeMessage.isBlank() ? "" : " (" + codeMessage + ")")
                    + (code > 0 ? " · code " + code : "")
                    + (httpStatus > 0 ? " · HTTP " + httpStatus : "");
        }
        return new ShippingProviderException(userMessage,
                rejected || !sideEffectPossible ? ShippingProviderException.Outcome.REJECTED
                        : ShippingProviderException.Outcome.UNKNOWN,
                diagnostics, notFound);
    }

    private Map<String, Object> parseJsonObject(String raw) {
        try {
            if (raw == null || raw.isBlank()) return Map.of();
            return object(mapper.readValue(raw, new TypeReference<Map<String, Object>>() { }));
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> responseDiagnostics(int httpStatus, Map<String, Object> response) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("httpStatus", httpStatus);
        long code = number(response.get("code"), 0);
        if (code > 0) diagnostics.put("code", code);
        for (String field : List.of("message", "code_message", "code_message_value")) {
            String value = safeText(firstText(response, field), 300);
            if (!value.isBlank()) diagnostics.put(field, value);
        }
        return diagnostics;
    }

    private Map<String, Object> detailDiagnostics(Map<String, Object> data) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        putIfPresent(diagnostics, "orderCode", firstText(data, "order_code", "OrderCode"));
        putIfPresent(diagnostics, "clientOrderCode", firstText(data, "client_order_code", "ClientOrderCode"));
        putIfPresent(diagnostics, "shopId", firstValue(data, "shop_id", "ShopId"));
        putIfPresent(diagnostics, "codAmount", firstValue(data, "cod_amount", "CODAmount"));
        putIfPresent(diagnostics, "providerStatus", firstText(data, "status", "Status"));
        return Map.copyOf(diagnostics);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private String toJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ignored) { return ""; }
    }

    private Object firstValue(Map<String, Object> source, String... names) {
        if (source == null) return null;
        for (String name : names) {
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
            }
        }
        return null;
    }

    private String firstText(Map<String, Object> source, String... names) {
        Object value = firstValue(source, names);
        return value == null ? "" : String.valueOf(value);
    }

    private String digits(String value) { return value == null ? "" : value.replaceAll("\\D", ""); }

    private String comparable(String value) {
        return Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String safeText(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replaceAll("(?i)(token|secret|authorization|password)\\s*[:=]\\s*\\S+",
                "$1=[REDACTED]").replaceAll("[\\r\\n\\t]+", " ").trim();
        return cleaned.substring(0, Math.min(max, cleaned.length()));
    }

    private FulfillmentStatus status(String raw) {
        return switch (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) {
            case "ready_to_pick", "picking", "money_collect_picking" -> FulfillmentStatus.PICKUP_PENDING;
            case "picked", "storing", "sorting", "transporting" -> FulfillmentStatus.PICKED_UP;
            case "delivering", "money_collect_delivering" -> FulfillmentStatus.IN_TRANSIT;
            case "delivered" -> FulfillmentStatus.DELIVERED;
            case "delivery_fail" -> FulfillmentStatus.DELIVERY_FAILED;
            case "waiting_to_return", "return", "return_transporting", "return_sorting",
                    "returning", "return_fail" -> FulfillmentStatus.RETURNING;
            case "returned" -> FulfillmentStatus.RETURNED;
            case "cancel" -> FulfillmentStatus.CANCELLED;
            default -> null;
        };
    }

    private String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        return headers.entrySet().stream().filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private String phone(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        if (digits.startsWith("84") && digits.length() >= 10) digits = "0" + digits.substring(2);
        if (digits.length() < 9 || digits.length() > 15) {
            throw new IllegalArgumentException("Số điện thoại nhận hàng không hợp lệ cho GHN.");
        }
        return digits;
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    private Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private long number(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(text(value)); } catch (NumberFormatException ignored) { return fallback; }
    }
    private Long numberOrNull(Object value) {
        if (value == null) return null;
        long parsed = number(value, -1);
        return parsed < 0 ? null : parsed;
    }
    private Instant instant(Object value) {
        if (value == null || text(value).isBlank()) return null;
        try { return Instant.parse(text(value)); }
        catch (RuntimeException ignored) {
            try { return java.time.OffsetDateTime.parse(text(value)).toInstant(); }
            catch (RuntimeException malformed) {
                throw new IllegalArgumentException("GHN Detail updated_date không đúng định dạng ISO-8601.");
            }
        }
    }

    private Instant webhookInstant(String value) {
        try { return Instant.parse(value); }
        catch (RuntimeException ignored) {
            try { return java.time.OffsetDateTime.parse(value).toInstant(); }
            catch (RuntimeException malformed) {
                throw new IllegalArgumentException("GHN webhook Time không đúng định dạng ISO-8601.");
            }
        }
    }

    private static RestClient configuredClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(3));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(8));
        return builder.requestFactory(requestFactory).build();
    }

}
