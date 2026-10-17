package com.utewedding.app.dto.response;

import com.utewedding.app.domain.OverallFulfillmentStatus;
import com.utewedding.app.domain.CheckoutGroupPaymentStatus;
import com.utewedding.app.domain.CheckoutGroupFulfillmentStatus;
import com.utewedding.app.domain.PaymentStatus;
import com.utewedding.app.domain.PaymentMethod;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Group placement/read response. Child order amounts remain authoritative per shop. */
public record MultiShopCheckoutResponse(
        UUID groupId,
        String groupNumber,
        long totalAmount,
        String currency,
        PaymentMethod paymentMethod,
        Instant expiresAt,
        CheckoutGroupPaymentStatus paymentStatus,
        CheckoutGroupFulfillmentStatus fulfillmentSummary,
        List<ChildOrder> orders) {

    public MultiShopCheckoutResponse {
        orders = orders == null ? List.of() : List.copyOf(orders);
    }

    public record ChildOrder(
            UUID orderId,
            String orderNumber,
            UUID shopId,
            String shopName,
            long totalAmount,
            PaymentStatus paymentStatus,
            OverallFulfillmentStatus fulfillmentStatus) {
    }
}
