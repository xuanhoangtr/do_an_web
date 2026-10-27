/*
 * Decompiled with CFR 0.152.
 */
package com.utewedding.app.dto.response;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.OrderStatus;
import com.utewedding.app.domain.PaymentMethod;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.dto.response.DeliveryAttemptResponse;
import com.utewedding.app.dto.response.FulfillmentItemResponse;
import com.utewedding.app.dto.response.FulfillmentTimelineEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record FulfillmentResponse(UUID id, UUID orderId, String orderNumber, FulfillmentStatus status, UUID shipperId, String trackingNumber, Instant deliveredAt, String deliveryProofUrl, Instant createdAt, Instant updatedAt, List<UUID> orderItemIds, String shippingRecipientName, String shippingPhone, String shippingAddress, String shippingWard, String shippingDistrict, String shippingProvince, OrderStatus orderStatus, PaymentStatus orderPaymentStatus, List<FulfillmentItemResponse> items, PaymentMethod paymentMethod, boolean collectsCod, long codAmount, List<DeliveryAttemptResponse> attempts, List<FulfillmentTimelineEvent> timeline, String codCollectionReceiptCode, ShipperContext shipperContext) {
    public FulfillmentResponse(UUID id, UUID orderId, String orderNumber, FulfillmentStatus status, UUID shipperId, String trackingNumber, Instant deliveredAt, String deliveryProofUrl, Instant createdAt, Instant updatedAt, List<UUID> orderItemIds, String shippingRecipientName, String shippingPhone, String shippingAddress, String shippingWard, String shippingDistrict, String shippingProvince, OrderStatus orderStatus, PaymentStatus orderPaymentStatus, List<FulfillmentItemResponse> items, PaymentMethod paymentMethod, boolean collectsCod, long codAmount, List<DeliveryAttemptResponse> attempts, List<FulfillmentTimelineEvent> timeline) {
        this(id, orderId, orderNumber, status, shipperId, trackingNumber, deliveredAt, deliveryProofUrl, createdAt, updatedAt, orderItemIds, shippingRecipientName, shippingPhone, shippingAddress, shippingWard, shippingDistrict, shippingProvince, orderStatus, orderPaymentStatus, items, paymentMethod, collectsCod, codAmount, attempts, timeline, null, null);
    }

    public FulfillmentResponse(UUID id, UUID orderId, String orderNumber, FulfillmentStatus status, UUID shipperId, String trackingNumber, Instant deliveredAt, String deliveryProofUrl, Instant createdAt, Instant updatedAt, List<UUID> orderItemIds, String shippingRecipientName, String shippingPhone, String shippingAddress, String shippingWard, String shippingDistrict, String shippingProvince, OrderStatus orderStatus, PaymentStatus orderPaymentStatus, List<FulfillmentItemResponse> items, PaymentMethod paymentMethod, boolean collectsCod, long codAmount, List<DeliveryAttemptResponse> attempts, List<FulfillmentTimelineEvent> timeline, String codCollectionReceiptCode) {
        this(id, orderId, orderNumber, status, shipperId, trackingNumber, deliveredAt, deliveryProofUrl, createdAt, updatedAt, orderItemIds, shippingRecipientName, shippingPhone, shippingAddress, shippingWard, shippingDistrict, shippingProvince, orderStatus, orderPaymentStatus, items, paymentMethod, collectsCod, codAmount, attempts, timeline, codCollectionReceiptCode, null);
    }

    public FulfillmentResponse {
        orderItemIds = orderItemIds == null ? List.of() : List.copyOf(orderItemIds);
        items = items == null ? List.of() : List.copyOf(items);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
    }

    public int itemTypeCount() {
        return this.items.size();
    }

    public int totalQuantity() {
        return this.items.stream().mapToInt(item -> item.quantity() == null ? 0 : item.quantity()).sum();
    }

    public boolean orderDeliveryBlocked() {
        return this.orderStatus != null && this.orderStatus.isTerminal() || this.orderPaymentStatus == PaymentStatus.REFUNDED;
    }

    public String phoneLink() {
        if (this.shippingPhone == null || this.shippingPhone.isBlank()) {
            return null;
        }
        String number = this.shippingPhone.replaceAll("[^0-9+]", "");
        return number.isBlank() ? null : "tel:" + number;
    }

    public String fullShippingAddress() {
        ArrayList<String> parts = new ArrayList<String>();
        FulfillmentResponse.addAddressPart(parts, this.shippingAddress);
        FulfillmentResponse.addAddressPart(parts, this.shippingWard);
        FulfillmentResponse.addAddressPart(parts, this.shippingDistrict);
        FulfillmentResponse.addAddressPart(parts, this.shippingProvince);
        return String.join((CharSequence)", ", parts);
    }

    public String mapSearchUrl() {
        String address = this.fullShippingAddress();
        if (address.isBlank()) {
            return null;
        }
        return "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(address, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void addAddressPart(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    public record ShipperContext(String shopName, String pickupContactName, String pickupPhone,
                                 String pickupAddress, String pickupProvinceCode, String pickupWardCode) { }
}
